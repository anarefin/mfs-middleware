package mfs.mw.orchestrator.dto;

/** Answer of the test-only {@code POST /test/v1/inquiries}: the transaction the inquiry was driven for. */
public record InquiryAccepted(String txnId) {}
