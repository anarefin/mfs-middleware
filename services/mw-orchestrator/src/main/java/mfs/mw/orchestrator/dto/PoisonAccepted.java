package mfs.mw.orchestrator.dto;

/** Answer of the test-only {@code POST /test/v1/poison-requests}: the record's key and where it ends. */
public record PoisonAccepted(String txnId, String dlqTopic) {}
