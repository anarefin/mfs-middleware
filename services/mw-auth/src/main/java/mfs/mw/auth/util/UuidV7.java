package mfs.mw.auth.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7 (48-bit Unix milliseconds, version 7, variant 10, random rest): the {@code jti} of
 * a transaction assertion (ticket 71; copied from mw-core-mock, D14).
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  public static UUID at(Instant at) {
    long ms = at.toEpochMilli();
    long msb = (ms << 16) | 0x7000L | (RANDOM.nextInt() & 0x0fffL);
    long lsb = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
