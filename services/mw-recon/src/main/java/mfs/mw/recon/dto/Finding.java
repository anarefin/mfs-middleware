package mfs.mw.recon.dto;

/**
 * One classified line or one completed transaction the file does not carry. {@code detail}
 * {@code LEDGER_MISSING} is a {@link ReconClass#STATUS_MISMATCH}: Upay COMPLETED, no core posting.
 */
public record Finding(
    ReconClass classification,
    String detail,
    String owner,
    String txnId,
    String partnerRef,
    String reference,
    Long fileAmountMinor,
    Long upayAmountMinor,
    Long ledgerAmountMinor) {}
