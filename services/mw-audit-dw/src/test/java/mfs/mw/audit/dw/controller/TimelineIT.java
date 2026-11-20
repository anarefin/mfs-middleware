package mfs.mw.audit.dw.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import mfs.mw.audit.dw.MutableClock;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.security.InternalTokens;
import mfs.mw.audit.dw.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 130 (FRS AUD-04): {@code GET /internal/v1/audit/transactions/{txnId}/timeline}. A top-up as
 * the stack produces it (channel request in, saga state changes, the core hold, the partner call
 * with its bodies, the notification), delivered out of order as Kafka may, comes back as every hop
 * in the order it happened, with each hop's latency, the time since the first hop and since the
 * previous one, and the masked payloads.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "mw.audit-dw.consumers.enabled=false")
@Import({TestInfrastructure.class, AuditSearchApiIT.TestClock.class})
class TimelineIT {

  private static final String FAKE_READER_PASSWORD = "test-only-reader-pw";
  private static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7b01";
  private static final String OTHER = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7b02";

  private final AuditService audit;
  private final MutableClock clock;
  private final JdbcTemplate admin;
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired
  TimelineIT(AuditService audit, Clock clock, DataSource dataSource) {
    this.audit = audit;
    this.clock = (MutableClock) clock;
    this.admin = new JdbcTemplate(dataSource);
    admin.execute("alter role audit_reader login password '" + FAKE_READER_PASSWORD + "'");
  }

  @BeforeEach
  void clean() {
    clock.set(AuditSearchApiIT.NOV);
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head, audit.payload");
  }

  private AuditEvent event(String txn, String service, String action, String resource, String at, String extra) {
    return json.readValue(
        """
        {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-topup",
         "actor":{"type":"system","id":"%s"},"action":"%s","resource":"%s",
         "outcome":"SUCCESS","at":"%s","service":"%s"%s}
        """
            .formatted(UUID.randomUUID(), txn, service, action, resource, at, service, extra),
        AuditEvent.class);
  }

  private List<AuditEvent> topUp() {
    List<AuditEvent> hops = new ArrayList<>();
    hops.add(event(TXN, "mw-app-gateway", "channel.request.completed", "txn/" + TXN, "2026-11-03T08:00:00.000Z",
        ",\"hop\":\"CHANNEL_IN\",\"direction\":\"IN\",\"durationMs\":950,\"payload\":["
            + "{\"direction\":\"IN\",\"contentType\":\"application/json\",\"body\":\"{\\\"msisdn\\\":\\\"01711234567\\\",\\\"amount\\\":5000}\"},"
            + "{\"direction\":\"OUT\",\"contentType\":\"application/json\",\"body\":\"{\\\"status\\\":\\\"RECEIVED\\\"}\"}]"));
    hops.add(event(TXN, "mw-orchestrator", "txn.state.changed", "txn/" + TXN, "2026-11-03T08:00:00.040Z",
        ",\"after\":{\"status\":\"RECEIVED\"}"));
    hops.add(event(TXN, "mw-orchestrator", "core.hold.completed", "txn/" + TXN, "2026-11-03T08:00:00.100Z",
        ",\"hop\":\"CORE_CALL\",\"direction\":\"OUT\",\"durationMs\":60"));
    hops.add(event(TXN, "mw-adapter-telco", "partner.call.completed", "partner/ocs/PAY", "2026-11-03T08:00:00.400Z",
        ",\"hop\":\"PARTNER_CALL\",\"direction\":\"OUT\",\"durationMs\":184,\"payloadRef\":\"partner-payload/6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b\",\"payload\":["
            + "{\"direction\":\"OUT\",\"contentType\":\"application/json\",\"body\":\"{\\\"msisdn\\\":\\\"0171****567\\\"}\"},"
            + "{\"direction\":\"IN\",\"contentType\":\"application/json\",\"body\":\"{\\\"result\\\":\\\"OK\\\"}\"}]"));
    hops.add(event(TXN, "mw-orchestrator", "txn.state.changed", "txn/" + TXN, "2026-11-03T08:00:00.450Z",
        ",\"after\":{\"status\":\"COMPLETED\"}"));
    hops.add(event(TXN, "mw-notify", "notification.sms.sent", "txn/" + TXN, "2026-11-03T08:00:00.900Z",
        ",\"hop\":\"NOTIFICATION\",\"direction\":\"OUT\",\"durationMs\":35"));
    return hops;
  }

  private JsonNode timeline(String txn) throws Exception {
    HttpResponse<String> res =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/internal/v1/audit/transactions/" + txn + "/timeline"))
                    .header("Authorization", InternalTokens.bearer("mw-admin-api"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    return json.readTree(res.body());
  }

  @Test
  void aTopUpGivesEveryHopInOrderWithLatenciesAndMaskedPayloads() throws Exception {
    List<AuditEvent> hops = topUp();
    Collections.reverse(hops); // delivered out of order
    hops.forEach(audit::record);
    audit.record(event(OTHER, "mw-orchestrator", "txn.state.changed", "txn/" + OTHER, "2026-11-03T08:00:00.200Z", ""));

    JsonNode t = timeline(TXN);

    assertThat(t.path("txnId").asString()).isEqualTo(TXN);
    assertThat(t.path("hops").valueStream().map(h -> h.path("hop").asString()).toList())
        .containsExactly("CHANNEL_IN", "ORCHESTRATOR", "CORE_CALL", "PARTNER_CALL", "ORCHESTRATOR", "NOTIFICATION");
    assertThat(t.path("hops").valueStream().map(h -> h.path("sinceStartMs").asLong()).toList())
        .containsExactly(0L, 40L, 100L, 400L, 450L, 900L);
    assertThat(t.path("totalMs").asLong()).isEqualTo(900);

    JsonNode partner = t.path("hops").get(3);
    assertThat(partner.path("durationMs").asLong()).isEqualTo(184);
    assertThat(partner.path("gapMs").asLong()).isEqualTo(300);
    assertThat(partner.path("direction").asString()).isEqualTo("OUT");
    assertThat(partner.path("payloads").valueStream().map(p -> p.path("direction").asString()).toList())
        .containsExactly("OUT", "IN");
    assertThat(partner.path("payloads").get(0).path("sha256").asString()).hasSize(64);
    assertThat(t.path("hops").get(0).has("gapMs")).isFalse();

    // the channel body arrived with a clear MSISDN: stored and shown masked only
    assertThat(t.toString()).doesNotContain("01711234567").contains("0171****567");
    assertThat(t.has("anchor")).isTrue();
  }

  @Test
  void anUnknownTransactionHasAnEmptyTimeline() throws Exception {
    JsonNode t = timeline("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7bff");

    assertThat(t.path("hops").size()).isZero();
    assertThat(t.path("totalMs").asLong()).isZero();
  }
}
