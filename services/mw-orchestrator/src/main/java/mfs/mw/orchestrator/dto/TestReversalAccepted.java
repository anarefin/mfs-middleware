package mfs.mw.orchestrator.dto;

/** Answer of the test-only {@code POST /test/v1/reversals}: the published REVERSAL's txnId and key. */
public record TestReversalAccepted(String txnId, String idempotencyKey) {}
