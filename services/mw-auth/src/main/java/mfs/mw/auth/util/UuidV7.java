package mfs.mw.auth.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/** RFC 9562 UUIDv7 (time ordered): the {@code txnId} of a command that is not a transaction (ticket 70). */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  public static UUID at(Instant at) {
    long ms = at.toEpochMilli();
    long msb = (ms << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
    long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
