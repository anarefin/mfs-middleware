package mfs.mw.recon.dto;

import java.time.Instant;

/**
 * One exception of the work queue (ticket 127, UC-22). {@code suggestedAction} is advice only: the action itself
 * is a maker-checker change request in mw-admin-api; mw-recon never moves money or a transaction state.
 */
public record ExceptionView(
    String id,
    String reconClass,
    String detail,
    String owner,
    String partnerId,
    String txnId,
    String partnerRef,
    String reference,
    Long fileAmountMinor,
    Long upayAmountMinor,
    Long ledgerAmountMinor,
    String state,
    Instant createdAt,
    long ageHours,
    String suggestedAction,
    String explanation,
    String explainedBy,
    Instant explainedAt,
    String action,
    String changeRequestId,
    String actionedBy) {}
