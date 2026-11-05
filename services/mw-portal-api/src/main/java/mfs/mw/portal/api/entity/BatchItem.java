package mfs.mw.portal.api.entity;

import java.util.UUID;
import mfs.mw.portal.api.util.Sensitive;
import mfs.mw.portal.api.util.SensitiveType;

/** Row of {@code bulk.batch_item} (own schema only); never leaves the service layer. */
public record BatchItem(
    UUID batchId,
    int lineNo,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    long amountMinor,
    String reference,
    ItemStatus status,
    UUID txnId,
    String errorCode) {}
