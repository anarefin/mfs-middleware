package mfs.mw.adapter.bank.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.common.header.Header;

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

  /**
   * Failed deliveries so far: {@code n} on {@code <topic>.<group>.retry.<n>}; on the DLQ every
   * failed delivery ({@code n+1} after the last retry, 1 for a record that was not retried).
   */
  public static final String RETRY_ATTEMPT = "mw-retry-attempt";
  /**
   * Epoch millis before which the retry consumer does not process the record (diagnostic; Spring
   * Kafka's own {@code retry_topic-backoff-timestamp} holds the record back). Not on the DLQ.
   */
  public static final String RETRY_NOT_BEFORE = "mw-retry-not-before";
  /** Source topic the record was first consumed from. */
  public static final String ORIGINAL_TOPIC = "mw-original-topic";
  /** Exception class of the last failure; never the message, which may carry payload data. */
  public static final String ERROR_CLASS = "mw-error-class";

  private MwHeaders() {}

  /**
   * A Kafka record header as UTF-8 text; null when the header (for example {@code
   * record.headers().lastHeader(name)}) or its value is absent.
   */
  public static String text(Header header) {
    if (header == null || header.value() == null) {
      return null;
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
