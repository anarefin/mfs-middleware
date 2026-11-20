package mfs.mw.portal.api.entity;

import java.util.List;
import java.util.UUID;
import mfs.mw.portal.api.util.Sensitive;
import mfs.mw.portal.api.util.SensitiveType;

/**
 * Row of {@code bulk.batch_item} (own schema only); never leaves the service layer. An INVALID row
 * (ticket 129b) has no MSISDN, an amount and reference only when they passed their rule, and its
 * {@code rowErrors}; every other row has all three and no row errors.
 */
public record BatchItem(
    UUID batchId,
    int lineNo,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    Long amountMinor,
    String reference,
    ItemStatus status,
    UUID txnId,
    String errorCode,
    List<String> rowErrors) {

  public BatchItem {
    rowErrors = rowErrors == null ? List.of() : List.copyOf(rowErrors);
  }
}
