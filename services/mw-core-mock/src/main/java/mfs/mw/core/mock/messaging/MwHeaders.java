package mfs.mw.core.mock.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Kafka record header names (spec 6.1, 21.3) and the retry/DLQ diagnostics this service adds. */
public final class MwHeaders {

  public static final String CORRELATION_ID = "mw-correlation-id";
  public static final String TXN_ID = "mw-txn-id";
  public static final String IDEMPOTENCY_KEY = "mw-idempotency-key";
  public static final String SOURCE = "mw-source";
  public static final String SCHEMA_VERSION = "mw-schema-version";
  public static final String TRACEPARENT = "traceparent";

  /** Headers every published record must carry (6.1). */
  public static final List<String> REQUIRED =
      List.of(CORRELATION_ID, TXN_ID, IDEMPOTENCY_KEY, SOURCE, SCHEMA_VERSION, TRACEPARENT);

  /** Retry step that produced this record: 1..n on {@code <topic>.retry.<n>}, n+1 on the DLQ. */
  public static final String RETRY_ATTEMPT = "mw-retry-attempt";
  /** Epoch millis before which a retry consumer must not process the record. */
  public static final String RETRY_NOT_BEFORE = "mw-retry-not-before";
  /** Source topic the record was first consumed from. */
  public static final String ORIGINAL_TOPIC = "mw-original-topic";
  /** Exception class of the last failure; never the message, which may carry payload data. */
  public static final String ERROR_CLASS = "mw-error-class";

  private MwHeaders() {}

  /**
   * A header value as text: Kafka record headers arrive as UTF-8 bytes, values set inside a route
   * are plain objects. Null when the header is absent.
   */
  public static String text(Object value) {
    if (value instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return value == null ? null : value.toString();
  }
}
