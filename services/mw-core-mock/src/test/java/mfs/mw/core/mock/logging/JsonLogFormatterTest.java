package mfs.mw.core.mock.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Field-level masking of the JSON log line (18.3, 21.1). */
class JsonLogFormatterTest {

  private final JsonLogFormatter formatter =
      new JsonLogFormatter(new MockEnvironment().withProperty("spring.application.name", "mw-x"));

  private static Map<String, Object> format(
      JsonLogFormatter formatter, Map<String, String> mdc, Object... keyValues) {
    LoggingEvent event = new LoggingEvent();
    event.setLevel(Level.INFO);
    event.setLoggerName("test");
    event.setThreadName("main");
    event.setMessage("m");
    event.setInstant(Instant.parse("2026-11-04T08:00:00Z"));
    event.setMDCPropertyMap(mdc);
    for (int i = 0; i < keyValues.length; i += 2) {
      event.addKeyValuePair(new KeyValuePair((String) keyValues[i], keyValues[i + 1]));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> json =
        JsonMapper.builder().build().readValue(formatter.format(event), Map.class);
    return json;
  }

  @Test
  void numbersThatHoldPersonalDataAreMaskedAndOthersStayNumbers() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of(),
            "msisdn", 8801712345789L,
            "account", new java.math.BigInteger("123456789012345678901"),
            "amount", 500,
            "durationMs", 12L);
    assertThat(json)
        .containsEntry("msisdn", "880171****789")
        .containsEntry("account", "****8901")
        .containsEntry("amount", 500)
        .containsEntry("durationMs", 12);
  }

  /** System-generated ids of their exact shape are kept whole. */
  @Test
  void systemIdsOfTheirExactShapeAreNotScrubbed() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of(
                "traceId", "4bf92f3577b34da6a3ce929d0e0e4736",
                "spanId", "00f067aa0ba902b7",
                "correlationId", "f47ac10b-58cc-4372-a567-0e02b2c3d479"),
            "txnId", "0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e0f");
    assertThat(json)
        .containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736")
        .containsEntry("spanId", "00f067aa0ba902b7")
        .containsEntry("correlationId", "f47ac10b-58cc-4372-a567-0e02b2c3d479")
        .containsEntry("txnId", "0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e0f");
  }

  /**
   * Ids are traced by exact match (edge test {@code cid_kept}), so a digit run inside one is not
   * read as an NID or account number: a random hex suffix holds a run of 10 digits often enough
   * to make the log untraceable.
   */
  @Test
  void idFieldsKeepLongDigitRunsWhole() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of(
                "traceId", "12345678901234567890123456789012",
                "spanId", "a012345678901234",
                "correlationId", "edge-test-cid-6948355579fa",
                "partnerId", "desco-1990123456789"),
            "txnId", "0190a7c4-0000-7000-8000-000000000001");
    assertThat(json)
        .containsEntry("traceId", "12345678901234567890123456789012")
        .containsEntry("spanId", "a012345678901234")
        .containsEntry("correlationId", "edge-test-cid-6948355579fa")
        .containsEntry("partnerId", "desco-1990123456789")
        .containsEntry("txnId", "0190a7c4-0000-7000-8000-000000000001");
  }

  @Test
  void idFieldsOfAnyShapeAreKeptWhole() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of("spanId", "A012345678901234", "correlationId", "cid:2026.11.05_00112233445566"),
            "txnId", "0190A7C4-0000-7000-8000-000000000001");
    assertThat(json)
        .containsEntry("spanId", "A012345678901234")
        .containsEntry("correlationId", "cid:2026.11.05_00112233445566")
        .containsEntry("txnId", "0190A7C4-0000-7000-8000-000000000001");
  }

  /**
   * correlationId and traceId are chosen by the caller (the filter checks only the character
   * set), so a mobile number put in one is still masked. A random id has the exact MSISDN shape
   * ({@code 01[3-9]} and 8 more digits, bounded by non-digits) about once in ten thousand.
   */
  @Test
  void anMsisdnInsideAnIdFieldIsStillMasked() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of("traceId", "trace-01712345789", "correlationId", "corr-01712345789"),
            "txnId", "app_8801812345678");
    assertThat(json)
        .containsEntry("traceId", "trace-0171****789")
        .containsEntry("correlationId", "corr-0171****789")
        .containsEntry("txnId", "app_880181****678");
  }

  /** The id exemption is by field name only: the same text anywhere else is scrubbed. */
  @Test
  void otherFieldsAndTheMessageGetNoIdExemption() {
    LoggingEvent event = new LoggingEvent();
    event.setLevel(Level.INFO);
    event.setLoggerName("test");
    event.setThreadName("main");
    event.setMessage("cid {} for {} wallet01712345789");
    event.setArgumentArray(new Object[] {"edge-test-cid-6948355579fa", "01812345678"});
    event.setInstant(Instant.parse("2026-11-04T08:00:00Z"));
    event.setMDCPropertyMap(Map.of("correlationId", "edge-test-cid-6948355579fa"));
    event.addKeyValuePair(new KeyValuePair("ref", "edge-test-cid-6948355579fa"));
    event.addKeyValuePair(new KeyValuePair("msisdn", "01912345678"));
    @SuppressWarnings("unchecked")
    Map<String, Object> json =
        JsonMapper.builder().build().readValue(formatter.format(event), Map.class);
    assertThat(json)
        .containsEntry("correlationId", "edge-test-cid-6948355579fa")
        .containsEntry("ref", "edge-test-cid-****579fa")
        .containsEntry("msisdn", "0191****678")
        .containsEntry(
            "message", "cid edge-test-cid-****579fa for 0181****678 wallet0171****789");
  }
}
