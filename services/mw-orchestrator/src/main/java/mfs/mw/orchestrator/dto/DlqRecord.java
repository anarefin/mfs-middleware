package mfs.mw.orchestrator.dto;

/** Answer of the test-only {@code GET /test/v1/dlq-records/{txnId}}: the record is parked on the DLQ. */
public record DlqRecord(String txnId, String topic, boolean parked) {}
