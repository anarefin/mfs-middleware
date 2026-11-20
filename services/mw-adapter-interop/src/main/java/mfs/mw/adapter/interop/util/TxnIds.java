package mfs.mw.adapter.interop.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/** New txnIds for inbound money (spec 5.1): UUIDv7 (RFC 9562), lower case. */
public final class TxnIds {

  private static final SecureRandom RANDOM = new SecureRandom();

  private TxnIds() {}

  public static String newTxnId(Instant now) {
    long millis = now.toEpochMilli();
    long randA = RANDOM.nextInt(1 << 12);
    long msb = (millis << 16) | 0x7000L | randA;
    long lsb = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
    return new UUID(msb, lsb).toString();
  }
}
