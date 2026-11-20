package mfs.mw.adapter.interop.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.interop.TestInfrastructure;
import mfs.mw.adapter.interop.contracts.ContractSchemas;
import mfs.mw.adapter.interop.dto.ConfigChangedEvent;
import mfs.mw.adapter.interop.messaging.PartnerAuditPublisher;
import mfs.mw.adapter.interop.partner.PartnerCall;
import mfs.mw.adapter.interop.partner.PartnerResponse;
import mfs.mw.adapter.interop.partner.config.PartnerConfigStore;
import mfs.mw.adapter.interop.partner.config.PartnerProperties;
import mfs.mw.adapter.interop.util.MaskingPolicy;
import mfs.mw.adapter.interop.util.SensitiveType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The raw payload store (ticket 97, FRS CON-05 / audit): every partner call's full request and
 * response, masked by the record's {@code sensitiveFields} and free-text scrubbing, lands in this
 * adapter's {@code partner_payload} table with its latency; the {@code mw.audit.event.v1} event keeps
 * the 4 KB excerpt and points at the rows with {@code payloadRef}; a call without a txnId (bill
 * fetch, dry-run) is audited too. Identical in telco, billpay and bank.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerPayloadIT {

  static final WireMockServer PARTNER = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String MSISDN = "01712345678";
  static final String ACCOUNT = "1234567890123";
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-000000000971";

  static {
    PARTNER.start();
  }

  private final PartnerConfigStore store;
  private final PartnerGateway gateway;
  private final JdbcTemplate jdbc;
  private final String family;

  @Autowired
  PartnerPayloadIT(PartnerConfigStore store, PartnerGateway gateway, JdbcTemplate jdbc, PartnerProperties properties) {
    this.store = store;
    this.gateway = gateway;
    this.jdbc = jdbc;
    this.family = properties.family();
  }

  @BeforeEach
  void publishRecord() {
    PARTNER.resetAll();
    if (store.instance("payco").isPresent()) {
      return;
    }
    String route = """
        - route:
            from:
              uri: direct:partner-payco
              steps:
                - to: "kamelet:mw-map?mapping=CALL.request"
                - to: "kamelet:mw-http-call?url=http://localhost:%d&method=POST&path=/v1/calls"
                - to: "kamelet:mw-classify?mapping=CALL.response&definiteStatuses=422"
                - to: "kamelet:mw-audit"
        """.formatted(PARTNER.port());
    store.apply(new ConfigChangedEvent(
        1, "partner/payco", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, 1,
        Map.of(
            "family", family,
            "allowedHosts", List.of("localhost"),
            "timeoutMs", 3000,
            "route", route,
            "sensitiveFields", Map.of("account", "ACCOUNT"),
            "mappings", Map.of(
                "CALL.request", "{\"ref\": reference, \"msisdn\": \"" + MSISDN + "\", \"account\": \"" + ACCOUNT + "\"}",
                "CALL.response", "status = 200 ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.receipt}"
                    + " : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}")),
        Instant.parse("2026-11-19T10:00:00Z"), "ops-maker01", "ops-checker01", "corr-971"));
    assertThat(store.instance("payco")).isPresent();
  }

  static void answer(String filler) {
    PARTNER.stubFor(post(urlEqualTo("/v1/calls")).willReturn(aResponse().withStatus(200).withFixedDelay(20)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"receipt\":\"PC-1\",\"echo\":\"" + MSISDN + "\",\"filler\":\"" + filler + "\"}")));
  }

  List<Map<String, Object>> payloads(String partnerId) {
    return jdbc.queryForList(
        "select txn_id, partner_id, op, direction, body, latency_ms, at, payload_ref from partner_payload"
            + " where partner_id = ? order by direction", partnerId);
  }

  JsonNode lastAudit(String key) {
    List<String> rows = jdbc.queryForList(
        "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class,
        PartnerAuditPublisher.AUDIT_TOPIC, key);
    assertThat(rows).as("audit rows for %s", key).isNotEmpty();
    JsonNode event = JSON.readTree(rows.getLast());
    assertThat(ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json").validate(event))
        .as("%s", event).isEmpty();
    return event;
  }

  @Test
  void theFullMaskedBodiesAreStoredWithTheLatencyAndTheAuditPointsAtThem() {
    String filler = "x".repeat(6000);
    answer(filler);

    PartnerResponse response = gateway.call(new PartnerCall("payco", "PAY", "BILL_PAY", TXN, "corr-971", "BITS-971", Map.of()));

    assertThat(response).isInstanceOf(PartnerResponse.Success.class);
    List<Map<String, Object>> rows = payloads("payco");
    assertThat(rows).extracting(r -> r.get("direction")).containsExactly("REQUEST", "RESPONSE");
    for (Map<String, Object> row : rows) {
      String body = (String) row.get("body");
      assertThat(row.get("txn_id")).isEqualTo(TXN);
      assertThat(row.get("op")).isEqualTo("PAY");
      assertThat(((Number) row.get("latency_ms")).longValue()).isGreaterThanOrEqualTo(20);
      assertThat(row.get("at")).isNotNull();
      assertThat(body).doesNotContain(MSISDN).contains(MaskingPolicy.mask(SensitiveType.MSISDN, MSISDN));
      assertThat(body).doesNotContain(ACCOUNT);
    }
    assertThat(rows.get(0).get("body").toString()).contains(MaskingPolicy.mask(SensitiveType.ACCOUNT, ACCOUNT));
    assertThat(rows.get(1).get("body").toString()).contains(filler).hasSizeGreaterThan(6000);

    JsonNode audit = lastAudit(TXN);
    assertThat(audit.path("payloadRef").asString()).isEqualTo(rows.get(0).get("payload_ref"));
    assertThat(audit.path("after").path("response").asString()).hasSizeLessThanOrEqualTo(4096 + 3);
    assertThat(audit.toString()).doesNotContain(MSISDN).doesNotContain(ACCOUNT);
  }

  @Test
  void aCallWithoutATxnIdIsAuditedAndStoredToo() {
    answer("short");

    gateway.call(new PartnerCall("payco", "INQUIRY", "BILL_PAY", null, "corr-972", null, Map.of()));

    JsonNode audit = lastAudit("payco");
    assertThat(audit.path("txnId").isMissingNode()).isTrue();
    assertThat(audit.path("resource").asString()).isEqualTo("partner/payco/INQUIRY");
    assertThat(jdbc.queryForObject(
        "select count(*) from partner_payload where partner_id = 'payco' and op = 'INQUIRY' and txn_id is null",
        Integer.class)).isEqualTo(2);
  }
}
