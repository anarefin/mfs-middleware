package mfs.mw.recon.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hex of a file body or a partner file id (REC-08). */
public final class Digests {

  private Digests() {}

  public static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256(String text) {
    return sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
