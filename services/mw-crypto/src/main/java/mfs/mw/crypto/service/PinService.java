package mfs.mw.crypto.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import mfs.mw.crypto.dto.DerivePinRequest;
import mfs.mw.crypto.dto.DerivePinResponse;
import mfs.mw.crypto.dto.EncryptedPin;
import mfs.mw.crypto.dto.PinHmac;
import mfs.mw.crypto.dto.PinPolicyInput;
import mfs.mw.crypto.dto.VerifyPinRequest;
import mfs.mw.crypto.dto.VerifyPinResponse;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.service.HsmKeys.Use;
import mfs.mw.crypto.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Wallet PIN verify and derive (spec 18.7, 18.8; caller mw-auth). The transport block is decrypted
 * under {@code PIN_TRANSPORT} (RSA-OAEP SHA-256, the HSM's raw RSA step), the PIN must be exactly 5
 * ASCII digits, and the stored value is HMAC-SHA256 under {@code PIN_HMAC} over {@code walletId ‖
 * 0x00 ‖ PIN}, compared in constant time. The clear PIN is a {@code byte[]}, zeroed in {@code
 * finally} together with every buffer that held it, and never a String, never logged, never
 * returned. A wrong PIN is {@code match: false}; a block that does not decrypt or holds no 5-digit PIN
 * is {@code MW-VAL-014}.
 */
@Service
public class PinService {

  static final int PIN_LENGTH = 5;
  private static final Logger log = LoggerFactory.getLogger(PinService.class);
  private static final Predicate<KeyPurpose> TRANSPORT = p -> "PIN_TRANSPORT".equals(p.name());
  private static final Predicate<KeyPurpose> PIN_HMAC = p -> "PIN_HMAC".equals(p.name());

  private final HsmKeys keys;

  public PinService(HsmKeys keys) {
    this.keys = keys;
  }

  /**
   * Whether the PIN in the block is the one {@code storedHmac} was made of. On a match under a
   * VERIFY_ONLY {@code PIN_HMAC} version the answer carries the value under the ACTIVE one.
   */
  public VerifyPinResponse verify(VerifyPinRequest r) {
    Resolved hmacKey = keys.resolve(r.hmacKeyId(), Use.CHECK, PIN_HMAC, "pin-verify");
    byte[] stored = Bytes.decode("storedHmac", r.storedHmac());
    byte[] pin = decrypt(r.encryptedPin(), "pin-verify");
    try {
      boolean match = MessageDigest.isEqual(pinHmac(hmacKey, r.walletId(), pin), stored);
      PinHmac rehash = null;
      if (match && hmacKey.key().state() == KeyState.VERIFY_ONLY) {
        Resolved active = keys.active(hmacKey.key().keyId());
        rehash = new PinHmac(Bytes.encode(pinHmac(active, r.walletId(), pin)), active.kid());
      }
      log.atInfo()
          .addKeyValue("event", "crypto.pin.verified")
          .addKeyValue("kid", hmacKey.kid())
          .addKeyValue("match", match)
          .addKeyValue("rehash", rehash != null)
          .log("PIN verified");
      return new VerifyPinResponse(match, rehash);
    } finally {
      Bytes.zero(pin);
    }
  }

  /** The HMAC to store for a new PIN, or the weak-PIN rule it breaks. */
  public DerivePinResponse derive(DerivePinRequest r) {
    Resolved active =
        r.hmacKeyId() != null
            ? keys.resolve(r.hmacKeyId(), Use.CREATE, PIN_HMAC, "pin-derive")
            : keys.activeByPurpose("PIN_HMAC", PIN_HMAC, "pin-derive");
    PinPolicyInput policy = r.policyInput() == null ? new PinPolicyInput(null, null, null, null) : r.policyInput();
    Resolved lastKey = null;
    byte[] lastHmac = null;
    if (policy.lastHmac() != null) {
      if (policy.lastHmacKeyId() == null) {
        throw CryptoErrors.invalidField("policyInput.lastHmacKeyId", "required with lastHmac");
      }
      lastKey = keys.resolve(policy.lastHmacKeyId(), Use.CHECK, PIN_HMAC, "pin-derive");
      lastHmac = Bytes.decode("policyInput.lastHmac", policy.lastHmac());
    }
    byte[] pin = decrypt(r.encryptedPin(), "pin-derive");
    try {
      Optional<String> rule = WeakPinPolicy.rule(pin, policy.msisdn(), policy.birthYear());
      if (rule.isEmpty() && lastKey != null && MessageDigest.isEqual(pinHmac(lastKey, r.walletId(), pin), lastHmac)) {
        rule = Optional.of(WeakPinPolicy.LAST_PIN);
      }
      if (rule.isPresent()) {
        log.atInfo()
            .addKeyValue("event", "crypto.pin.weak")
            .addKeyValue("rule", rule.get())
            .log("New PIN refused by the weak-PIN policy");
        return DerivePinResponse.weak(rule.get());
      }
      PinHmac h = new PinHmac(Bytes.encode(pinHmac(active, r.walletId(), pin)), active.kid());
      log.atInfo().addKeyValue("event", "crypto.pin.derived").addKeyValue("kid", active.kid()).log("PIN HMAC derived");
      return DerivePinResponse.of(h);
    } finally {
      Bytes.zero(pin);
    }
  }

  /** The clear PIN of the block: exactly 5 ASCII digits, else {@code MW-VAL-014}. The caller zeroes it. */
  private byte[] decrypt(EncryptedPin block, String operation) {
    Resolved transport = keys.resolve(block.keyId(), Use.CHECK, TRANSPORT, operation);
    byte[] ciphertext = Bytes.decode("encryptedPin.ciphertext", block.ciphertext());
    byte[] pin = keys.run("pin-decrypt", transport, p -> p.decryptOaep(transport.label(), ciphertext));
    if (!isPin(pin)) {
      Bytes.zero(pin);
      throw CryptoErrors.badPinBlock();
    }
    return pin;
  }

  static boolean isPin(byte[] pin) {
    if (pin == null || pin.length != PIN_LENGTH) {
      return false;
    }
    boolean digits = true;
    for (byte b : pin) {
      digits &= b >= '0' && b <= '9';
    }
    return digits;
  }

  /** HMAC-SHA256 under {@code key} over {@code walletId ‖ 0x00 ‖ PIN}; the message buffer is zeroed. */
  private byte[] pinHmac(Resolved key, String walletId, byte[] pin) {
    byte[] wallet = walletId.getBytes(StandardCharsets.UTF_8);
    byte[] message = new byte[wallet.length + 1 + pin.length];
    System.arraycopy(wallet, 0, message, 0, wallet.length);
    System.arraycopy(pin, 0, message, wallet.length + 1, pin.length);
    try {
      return keys.run("pin-hmac", key, p -> p.hmac(key.label(), List.of(message)).getFirst());
    } finally {
      Bytes.zero(message);
    }
  }
}
