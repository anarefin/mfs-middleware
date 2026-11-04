package mfs.mw.portal.api.entity;

import java.util.UUID;
import mfs.mw.portal.api.util.Sensitive;
import mfs.mw.portal.api.util.SensitiveType;

/** A row the dispatcher is about to send, with what its child txn needs from the batch. */
public record PendingChild(
    UUID batchId,
    int lineNo,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    long amountMinor,
    String reference,
    String orgId,
    String orgWallet,
    UUID parentTxnId,
    String currency) {}
