package mfs.mw.app.gateway.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Column encryption of a linked bank account number (ticket 117b, FRS FL-08). AES-256-GCM with a
 * random 96-bit IV; the wallet id is the associated data, so a ciphertext copied to another wallet's
 * row does not decrypt. Stored as {@code v1:<base64(iv || ciphertext || tag)>}. {@link #lookup} is
 * a keyed HMAC-SHA256 of the number for the uniqueness index, so equal numbers can be found without
 * decrypting. Both keys are derived from one secret with distinct labels. Never logs.
 */
public final class AccountCipher {

  private static final String VERSION = "v1:";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec encKey;
  private final SecretKeySpec macKey;
  private final SecureRandom random = new SecureRandom();

  public AccountCipher(String secret) {
    this.encKey = new SecretKeySpec(derive("linked-account-enc|", secret), "AES");
    this.macKey = new SecretKeySpec(derive("linked-account-idx|", secret), "HmacSHA256");
  }

  public String encrypt(String accountNumber, String walletId) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, iv));
      c.updateAAD(walletId.getBytes(StandardCharsets.UTF_8));
      byte[] ct = c.doFinal(accountNumber.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return VERSION + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("account encryption failed", e);
    }
  }

  public String decrypt(String stored, String walletId) {
    if (stored == null || !stored.startsWith(VERSION)) {
      throw new IllegalArgumentException("unknown ciphertext version");
    }
    try {
      byte[] in = Base64.getDecoder().decode(stored.substring(VERSION.length()));
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
      c.updateAAD(walletId.getBytes(StandardCharsets.UTF_8));
      return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("account decryption failed", e);
    }
  }

  /** Keyed hash of the number for lookups (hex). */
  public String lookup(String bankCode, String accountNumber) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(macKey);
      return HexFormat.of().formatHex(mac.doFinal((bankCode + "|" + accountNumber).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("account hash failed", e);
    }
  }

  private static byte[] derive(String label, String secret) {
    try {
      return MessageDigest.getInstance("SHA-256").digest((label + secret).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
