package mfs.mw.adapter.billpay.messaging;

/**
 * Per-record values behind the spec 6.1 headers. {@code mw-source} and {@code traceparent} are
 * added by {@link EventPublisher}.
 */
public record EventMetadata(
    String correlationId, String txnId, String idempotencyKey, int schemaVersion) {

  public EventMetadata {
    requireText(correlationId, "correlationId");
    requireText(txnId, "txnId");
    requireText(idempotencyKey, "idempotencyKey");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be >= 1");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required on every Kafka record (spec 6.1)");
    }
  }
}
