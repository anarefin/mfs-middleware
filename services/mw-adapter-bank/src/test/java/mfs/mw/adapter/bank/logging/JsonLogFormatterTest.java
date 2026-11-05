package mfs.mw.adapter.bank.logging;

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

  /** System-generated ids are kept whole. */
  @Test
  void systemIdsAreNotScrubbed() {
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
   * An id field is kept whole only when it has the system shape and the scrub leaves it unchanged:
   * a UUID, trace id or span id of the right shape that still holds a 10+ digit run or a (split)
   * MSISDN is written scrubbed. partnerId gets no exemption.
   */
  @Test
  void systemShapedIdsThatTheScrubWouldChangeAreScrubbed() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of(
                "traceId", "12345678901234567890123456789012",
                "spanId", "a012345678901234",
                "correlationId", "01a1aa26-3303-7169-8282-0e6132984047"),
            "txnId", "01a1aa26-3303-7169-8282-0e6132984047",
            "partnerId", "0190a7c4-0000-7000-8000-000000000002");
    assertThat(json)
        .containsEntry("traceId", "****9012")
        .containsEntry("spanId", "a****1234")
        .containsEntry("correlationId", "01a1aa26-3303-7169-8282-0e****047")
        .containsEntry("txnId", "01a1aa26-3303-7169-8282-0e****047")
        .containsEntry("partnerId", "0190a7c4-0000-7000-8000-****0002");
  }

  /** A valid RFC UUID that spreads an MSISDN over its groups (no 6-digit run) is scrubbed. */
  @Test
  void aValidUuidHidingASplitMsisdnIsScrubbed() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of("correlationId", "aaaa0171-2345-678a-8bcd-ef0123abcdef"),
            "txnId", "abcdef01-7123-4567-8abc-def012345abc");
    assertThat(json)
        .containsEntry("correlationId", "aaaa0171****678a-8bcd-ef0123abcdef")
        .containsEntry("txnId", "abcdef0171****678abc-def012345abc");
    assertThat(json.toString()).doesNotContain("2345-678", "7123-4567");
  }

  /**
   * Anything else in an id field is scrubbed like free text: a correlationId that is not a UUID
   * and holds a run of 6+ digits, a txnId that is not a valid lower-case RFC 9562 UUID (a crafted
   * UUID shape, upper case, caller text), a traceId / spanId that is not W3C hex, any partnerId.
   */
  @Test
  void idFieldsThatAreNotSystemIdsAreScrubbedLikeFreeText() {
    Map<String, Object> json =
        format(
            formatter,
            Map.of(
                "correlationId", "corr-2345678901234",
                "traceId", "trace-01712345789",
                "spanId", "A012345678901234"),
            "txnId", "app_8801812345678",
            "partnerId", "desco-123456789012");
    assertThat(json)
        .containsEntry("correlationId", "corr-****234")
        .containsEntry("traceId", "trace-0171****789")
        .containsEntry("spanId", "A****1234")
        .containsEntry("txnId", "app_880181****678")
        .containsEntry("partnerId", "desco-****9012");

    Map<String, Object> crafted =
        format(
            formatter,
            Map.of("correlationId", "12345678-1234-1234-1234-001711234567"),
            "txnId", "12345678-1234-1234-1234-001711234567");
    assertThat(crafted)
        .containsEntry("correlationId", "12345678-1234-1234-1234-****4567")
        .containsEntry("txnId", "12345678-1234-1234-1234-****4567");
    assertThat(format(formatter, Map.of(), "txnId", "0190A7C4-0000-7000-8000-000000000001"))
        .containsEntry("txnId", "0190A7C4-0000-7000-8000-****0001");
    // no run of 6 digits, so the boundary keeps it, but the scrub still masks a dashed MSISDN
    assertThat(format(formatter, Map.of("correlationId", "cid-0171-123-4567")))
        .containsEntry("correlationId", "cid-0171****567");
  }

  /** An id of another shape without a long digit run comes through the scrub unchanged. */
  @Test
  void otherIdsWithoutLongDigitRunsStayReadable() {
    Map<String, Object> json =
        format(
            formatter, Map.of("correlationId", "edge-test-cid-qfbhkcmxwazp"), "partnerId", "desco");
    assertThat(json)
        .containsEntry("correlationId", "edge-test-cid-qfbhkcmxwazp")
        .containsEntry("partnerId", "desco");
  }

  /**
   * The id exemption holds in the id fields only: elsewhere an id, a UUID included, is scrubbed
   * like any free text (MaskingPolicy.scrub). Message and customer-field masking is unchanged.
   */
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
    event.addKeyValuePair(
        new KeyValuePair("note", "edge-test-cid-6948355579fa 0190a7c4-0000-7000-8000-000000000001"));
    @SuppressWarnings("unchecked")
    Map<String, Object> json =
        JsonMapper.builder().build().readValue(formatter.format(event), Map.class);
    assertThat(json)
        .containsEntry("correlationId", "edge-test-cid-****579fa")
        .containsEntry("ref", "edge-test-cid-****579fa")
        .containsEntry("msisdn", "0191****678")
        .containsEntry("note", "edge-test-cid-****579fa 0190a7c4-0000-7000-8000-****0001")
        .containsEntry(
            "message", "cid edge-test-cid-****579fa for 0181****678 wallet0171****789");
  }
}
