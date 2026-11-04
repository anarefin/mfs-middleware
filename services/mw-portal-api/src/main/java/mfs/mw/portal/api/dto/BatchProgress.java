package mfs.mw.portal.api.dto;

/**
 * How far an approved batch is (ticket 34): rows not sent yet, sent and running, paid, not paid
 * (FAILED, REJECTED, REVERSED), and with operations (MANUAL_REVIEW); the amount paid so far.
 */
public record BatchProgress(
    int pending, int inFlight, int completed, int failed, int manualReview, Amount paidAmount) {}
