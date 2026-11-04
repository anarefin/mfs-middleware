package mfs.mw.ussd.util;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUID version 7 (RFC 9562): 48-bit Unix milliseconds, version 7, 74 random bits, variant 10.
 * Time-ordered, as {@code txnId} must be (spec 5.1, contract {@code TxnId}).
 */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  public static UUID next(Clock clock) {
    long millis = clock.millis();
    long randA = RANDOM.nextInt(1 << 12);
    long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
    long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randA;
    long lsb = 0x8000_0000_0000_0000L | randB;
    return new UUID(msb, lsb);
  }
}
