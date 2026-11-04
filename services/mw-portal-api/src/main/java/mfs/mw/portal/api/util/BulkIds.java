package mfs.mw.portal.api.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Ids of a bulk batch's transactions, derived from the batch id and the row (ticket 34), so a
 * re-run after a crash sends the very same txnId and idempotency key and the orchestrator answers
 * it from its idempotency record instead of paying again (spec 7.3). The txnIds keep the UUIDv7
 * shape the contract demands: the batch id's 48-bit timestamp, version 7, variant 10, and 74 bits
 * of SHA-256 over {@code bulk:<batchId>:<line>} in place of the random bits.
 */
public final class BulkIds {

  /** The row number of the parent BULK_DISBURSE txn (data rows start at line 2). */
  static final int PARENT = 0;

  private BulkIds() {}

  /** The parent BULK_DISBURSE txnId of the batch. */
  public static UUID parentTxnId(UUID batchId) {
    return derive(batchId, PARENT);
  }

  /** The child txnId of row {@code line}. */
  public static UUID childTxnId(UUID batchId, int line) {
    if (line <= PARENT) {
      throw new IllegalArgumentException("a data row has a positive line number");
    }
    return derive(batchId, line);
  }

  /** The child's idempotency key (at most 64 characters, contract IdempotencyKey). */
  public static String idempotencyKey(UUID batchId, int line) {
    return "bulk-" + batchId + "-" + line;
  }

  private static UUID derive(UUID batchId, int line) {
    byte[] h;
    try {
      h = MessageDigest.getInstance("SHA-256")
          .digest(("bulk:" + batchId + ":" + line).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    ByteBuffer b = ByteBuffer.wrap(h);
    long hi = b.getLong();
    long lo = b.getLong();
    long millis = batchId.getMostSignificantBits() >>> 16;
    long msb = millis << 16 | 0x7000L | (hi & 0x0FFFL);
    long lsb = 0x8000_0000_0000_0000L | (lo & 0x3FFF_FFFF_FFFF_FFFFL);
    return new UUID(msb, lsb);
  }
}
