package mfs.mw.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.PinProperties;
import mfs.mw.auth.dto.CryptoPinDerived;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.CredentialRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * A new wallet PIN through mw-crypto (spec 18.7, 18.8, D20): mw-auth holds only the PIN HMAC; the
 * ciphertext goes to mw-crypto {@code /pin/derive}, which runs the weak-PIN rules on the clear PIN
 * with the inputs passed here ({@link #policyInput}: the MSISDN, the birth year when the core
 * supplies it, the current PIN's HMAC). The older PINs of the reuse rule ({@code mw.auth.pin.history},
 * the current one included) are compared here: the HMAC is deterministic per key version, so a new
 * HMAC equal to a kept one under the same kid is the same PIN. Checking a PIN is {@link PinVerifier}.
 * Nothing about the PIN is logged.
 */
@Service
public class PinService {

  static final String LAST_PIN = "LAST_PIN";

  private final CryptoClient crypto;
  private final CredentialRepository credentials;
  private final PinProperties props;

  public PinService(CryptoClient crypto, CredentialRepository credentials, PinProperties props) {
    this.crypto = crypto;
    this.credentials = credentials;
    this.props = props;
  }

  /**
   * The HMAC to store for a new PIN of the wallet (first PIN, change, reset); 400 MW-AUTH-044 naming
   * the rule when it is weak or one of the last {@code history} PINs.
   */
  public PinHmac newPin(AuthWallet wallet, String msisdn, EncryptedPin pin) {
    Optional<CredentialRepository.Credential> current = credentials.credential(wallet.walletId());
    PinHmac h = derive(wallet.walletId(), pin, policyInput(msisdn, wallet.birthYear(),
        current.map(CredentialRepository.Credential::current).orElse(null)));
    if (current.isPresent()) {
      for (PinHmac old : current.get().earlier()) {
        if (old.hmacKeyId().equals(h.hmacKeyId()) && same(old.hmac(), h.hmac())) {
          throw weak(LAST_PIN);
        }
      }
    }
    return h;
  }

  /** The PINs kept for the reuse rule once {@code current} is replaced: it and the newest earlier ones. */
  public List<PinHmac> historyAfter(Optional<CredentialRepository.Credential> current) {
    List<PinHmac> kept = new ArrayList<>();
    current.ifPresent(c -> {
      kept.add(c.current());
      kept.addAll(c.earlier());
    });
    int keep = Math.max(0, props.history() - 1);
    return List.copyOf(kept.subList(0, Math.min(keep, kept.size())));
  }

  /**
   * The HMAC to store for a new PIN; 400 MW-AUTH-044 naming the rule when mw-crypto finds it weak
   * ({@code policyInput}: the rule inputs; null = the PIN's own rules only, the dev seed).
   */
  public PinHmac derive(String walletId, EncryptedPin pin, Map<String, Object> policyInput) {
    CryptoPinDerived d = crypto.derivePin(walletId, pin, policyInput);
    if (d.weak()) {
      throw weak(d.rule());
    }
    return new PinHmac(d.hmac(), d.hmacKeyId());
  }

  /** The {@code PinPolicyInput} of crypto-internal.v1; only the fields known. */
  static Map<String, Object> policyInput(String msisdn, Integer birthYear, PinHmac last) {
    Map<String, Object> in = new LinkedHashMap<>();
    if (msisdn != null) {
      in.put("msisdn", msisdn);
    }
    if (birthYear != null) {
      in.put("birthYear", birthYear);
    }
    if (last != null) {
      in.put("lastHmac", last.hmac());
      in.put("lastHmacKeyId", last.hmacKeyId());
    }
    return in;
  }

  private static boolean same(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
  }

  static MwException weak(String rule) {
    return new MwException(ErrorCodes.AUTH_WEAK_PIN, "This PIN is too easy to guess; choose another")
        .withStatus(HttpStatus.BAD_REQUEST)
        .withDetails(List.of(new ErrorDetail("encryptedPin", rule)));
  }

  static MwException notSet() {
    return new MwException(ErrorCodes.AUTH_PIN_NOT_SET, "No PIN set; sign in with an OTP and set one")
        .withStatus(HttpStatus.FORBIDDEN);
  }
}
