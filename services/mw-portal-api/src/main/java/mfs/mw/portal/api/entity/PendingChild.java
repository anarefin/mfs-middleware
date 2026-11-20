package mfs.mw.portal.api.entity;

import java.util.UUID;
import mfs.mw.portal.api.util.Sensitive;
import mfs.mw.portal.api.util.SensitiveType;

/**
 * A row the dispatcher is about to send, with what its child txn needs from the batch; {@code attempt}
 * is 0, or n for the n-th approved retry of the row (ticket 129c).
 */
public record PendingChild(
    UUID batchId,
    int lineNo,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    long amountMinor,
    String reference,
    String orgId,
    String orgWallet,
    UUID parentTxnId,
    String currency,
    int attempt) {

  /** The first attempt of a row. */
  public PendingChild(
      UUID batchId, int lineNo, String msisdn, long amountMinor, String reference, String orgId, String orgWallet,
      UUID parentTxnId, String currency) {
    this(batchId, lineNo, msisdn, amountMinor, reference, orgId, orgWallet, parentTxnId, currency, 0);
  }
}
