package mfs.mw.orchestrator.dto;

/** Answer of the test-only {@code GET /test/v1/txn-requests/{idempotencyKey}}. */
public record TxnRequestHandled(String idempotencyKey, boolean handled) {}
