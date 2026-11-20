package mfs.mw.simulators.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lower-case hex SHA-256 of bytes (file checksums of the file mocks, ticket 123). */
public final class Hashing {

  private Hashing() {}

  public static String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 missing", e);
    }
  }
}
