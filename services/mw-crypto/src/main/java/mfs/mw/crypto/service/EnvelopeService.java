package mfs.mw.crypto.service;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.function.Predicate;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.dto.Envelope;
import mfs.mw.crypto.dto.EnvelopeEncryptRequest;
import mfs.mw.crypto.dto.PlaintextView;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.service.HsmKeys.Use;
import mfs.mw.crypto.util.Bytes;
import org.springframework.stereotype.Service;

/**
 * Envelope encryption (spec 18.8: NID and account columns, files): every encrypt draws a fresh
 * AES-256 data key, encrypts AES-256-GCM (12-byte random nonce, 128-bit tag, the optional aad bound
 * in) here, and has the HSM wrap the data key under the KEK ({@code KEK_<purpose>}). Decrypt unwraps
 * in the HSM under the KEK version the envelope names (ACTIVE or VERIFY_ONLY). The data key is zeroed
 * after use. A tampered envelope is {@code MW-VAL-016}.
 */
@Service
public class EnvelopeService {

  static final int IV_BYTES = 12;
  static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Predicate<KeyPurpose> KEK = p -> p.capability() == Capability.ENVELOPE;

  private final HsmKeys keys;
  private final CallerPolicy callers;

  public EnvelopeService(HsmKeys keys, CallerPolicy callers) {
    this.callers = callers;
    this.keys = keys;
  }

  public Envelope encrypt(EnvelopeEncryptRequest r) {
    if ((r.kekId() == null) == (r.purpose() == null)) {
      throw CryptoErrors.invalidField("kekId", "exactly one of kekId and purpose");
    }
    if (r.purpose() != null) {
      callers.requirePurpose(r.purpose(), "envelope-encrypt");
    }
    Resolved kek =
        r.kekId() != null
            ? keys.resolve(r.kekId(), Use.CREATE, KEK, "envelope-encrypt")
            : keys.activeByPurpose(r.purpose(), KEK, "envelope-encrypt");
    callers.requirePurpose(kek.purpose().name(), "envelope-encrypt");
    byte[] plaintext = Bytes.decode("plaintext", r.plaintext());
    byte[] aad = r.aad() == null ? null : Bytes.decode("aad", r.aad());
    byte[] dataKey = new byte[32];
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(dataKey);
    RANDOM.nextBytes(iv);
    try {
      byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dataKey, iv, aad, plaintext);
      byte[] wrapped = keys.run("envelope-wrap", kek, p -> p.wrapKey(kek.label(), dataKey));
      return new Envelope(kek.kid(), Bytes.encode(wrapped), Bytes.encode(iv), Bytes.encode(ciphertext), r.aad());
    } catch (AEADBadTagException e) {
      throw new IllegalStateException(e);
    } finally {
      Bytes.zero(dataKey, plaintext);
    }
  }

  public PlaintextView decrypt(Envelope e) {
    Resolved kek = keys.resolve(e.kekId(), Use.CHECK, KEK, "envelope-decrypt");
    callers.requirePurpose(kek.purpose().name(), "envelope-decrypt");
    byte[] wrapped = Bytes.decode("wrappedKey", e.wrappedKey());
    byte[] iv = Bytes.decode("iv", e.iv());
    byte[] ciphertext = Bytes.decode("ciphertext", e.ciphertext());
    byte[] aad = e.aad() == null ? null : Bytes.decode("aad", e.aad());
    byte[] dataKey = keys.run("envelope-unwrap", kek, p -> p.unwrapKey(kek.label(), wrapped));
    byte[] plaintext = null;
    try {
      if (dataKey.length != 32) {
        throw CryptoErrors.badEnvelope();
      }
      plaintext = gcm(Cipher.DECRYPT_MODE, dataKey, iv, aad, ciphertext);
      return new PlaintextView(Bytes.encode(plaintext));
    } catch (AEADBadTagException bad) {
      throw CryptoErrors.badEnvelope();
    } finally {
      Bytes.zero(dataKey, plaintext);
    }
  }

  private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] aad, byte[] input) throws AEADBadTagException {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
      if (aad != null) {
        c.updateAAD(aad);
      }
      return c.doFinal(input);
    } catch (AEADBadTagException e) {
      throw e;
    } catch (GeneralSecurityException e) {
      throw new MwException(ErrorCodes.SYS_INTERNAL, "AES-GCM failed", e);
    }
  }
}
