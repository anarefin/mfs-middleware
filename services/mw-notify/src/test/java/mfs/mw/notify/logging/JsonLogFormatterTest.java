package mfs.mw.notify.logging;

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
   * The shape alone is no exemption: a UUID's last group or a trace id can be all digits, and
   * correlationId and traceId can come from the caller.
   */
  @Test
  void systemIdsHidingALongNumberAreScrubbed() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of(
                "traceId", "12345678901234567890123456789012",
                "spanId", "a012345678901234",
                "correlationId", "12345678-1234-1234-1234-001711234567"),
            "txnId", "0190a7c4-0000-7000-8000-000000000001");
    assertThat(json)
        .containsEntry("traceId", "****9012")
        .containsEntry("spanId", "a****1234")
        .containsEntry("correlationId", "12345678-1234-1234-1234-****4567")
        .containsEntry("txnId", "0190a7c4-0000-7000-8000-****0001");
  }

  @Test
  void idFieldsOfAnyOtherShapeAreScrubbed() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of(
                "traceId", "trace-01712345789",
                "spanId", "A012345678901234",
                "correlationId", "corr-01712345789"),
            "txnId", "0190A7C4-0000-7000-8000-000000000001");
    assertThat(json)
        .containsEntry("traceId", "trace-0171****789")
        .containsEntry("spanId", "A****1234")
        .containsEntry("correlationId", "corr-0171****789")
        .containsEntry("txnId", "0190A7C4-0000-7000-8000-****0001");
  }

  /** An id shape in any other field is no exemption. */
  @Test
  void otherFieldsGetNoIdExemption() {
    Map<String, Object> json =
        format(formatter, Map.of(), "partnerId", "0190a7c4-0000-7000-8000-000000000001");
    assertThat(json).containsEntry("partnerId", "0190a7c4-0000-7000-8000-****0001");
  }
}
