package mfs.mw.audit.dw.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import mfs.mw.audit.dw.MutableClock;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The internal audit search API (ticket 35, spec 14.3 / 16.2: mw-admin-api reads the audit trail
 * through it), over real HTTP. Events are stored through {@link AuditService} (as the consumer
 * does) and read back as the read-only {@code audit_reader}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "mw.audit-dw.consumers.enabled=false")
@Import({TestInfrastructure.class, AuditSearchApiIT.TestClock.class})
class AuditSearchApiIT {

  static final Instant NOV = Instant.parse("2026-11-03T08:15:31Z");

  /** Same test-only value as src/test/resources/config/application.yml; not a real secret. */
  private static final String FAKE_READER_PASSWORD = "test-only-reader-pw";

  private static final String TXN_A = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
  private static final String TXN_B = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(NOV);
    }
  }

  private final AuditService audit;
  private final MutableClock clock;
  private final JdbcTemplate admin;
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired
  AuditSearchApiIT(AuditService audit, Clock clock, DataSource dataSource) {
    this.audit = audit;
    this.clock = (MutableClock) clock;
    this.admin = new JdbcTemplate(dataSource);
    admin.execute("alter role audit_reader login password '" + FAKE_READER_PASSWORD + "'");
  }

  @BeforeEach
  void clean() {
    clock.set(NOV);
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head");
  }

  private void record(String txnId, String actorType, String actorId, String action, String at, String after) {
    audit.record(
        json.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-1",
             "actor":{"type":"%s","id":"%s"},"action":"%s","resource":"txn/%s",
             "after":%s,"outcome":"SUCCESS","at":"%s","service":"mw-orchestrator"}
            """
                .formatted(UUID.randomUUID(), txnId, actorType, actorId, action, txnId, after, at),
            AuditEvent.class));
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    // mw-admin-api's token (who may call: InternalApiAuthIT)
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
            .header("Authorization", mfs.mw.audit.dw.security.InternalTokens.bearer("mw-admin-api"))
            .build();
    return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode ok(String pathAndQuery) throws Exception {
    HttpResponse<String> res = get(pathAndQuery);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    return json.readTree(res.body());
  }

  private static List<String> actions(JsonNode page) {
    return page.path("items").valueStream().map(n -> n.path("action").asString()).toList();
  }

  @Test
  void searchByTxnIdListsItsEventsInTheOrderTheyHappened() throws Exception {
    record(TXN_A, "system", "mw-orchestrator", "txn.state.changed", "2026-11-03T08:15:31Z", "{\"status\":\"VALIDATING\"}");
    record(TXN_B, "system", "mw-orchestrator", "txn.state.changed", "2026-11-03T08:15:32Z", "{\"status\":\"VALIDATING\"}");
    record(TXN_A, "partner", "gp", "partner.call.completed", "2026-11-03T08:15:33Z", "{\"msisdn\":\"01711000002\"}");

    JsonNode page = ok("/internal/v1/audit/events?txnId=" + TXN_A);

    assertThat(actions(page)).containsExactly("txn.state.changed", "partner.call.completed");
    JsonNode first = page.path("items").get(0);
    assertThat(first.path("txnId").asString()).isEqualTo(TXN_A);
    assertThat(first.path("actor").path("id").asString()).isEqualTo("mw-orchestrator");
    assertThat(first.path("after").path("status").asString()).isEqualTo("VALIDATING");
    assertThat(first.has("recordedAt")).isTrue();
    // stored masked (spec 18.3), so returned masked
    assertThat(page.toString()).doesNotContain("01711000002").contains("0171****002");
  }

  @Test
  void searchByActorActionAndTimeWindow() throws Exception {
    record(TXN_A, "user", "ops.maker1", "txn.reversal.requested", "2026-11-03T08:00:00Z", "{}");
    record(TXN_B, "user", "ops.checker1", "txn.reversal.approved", "2026-11-03T09:00:00Z", "{}");
    record(TXN_B, "system", "mw-orchestrator", "txn.state.changed", "2026-11-03T09:30:00Z", "{}");

    // operator usernames are staff data and stay unmasked (M1-M2 gate decision)
    JsonNode byActor = ok("/internal/v1/audit/events?actorId=ops.maker1");
    assertThat(actions(byActor)).containsExactly("txn.reversal.requested");
    assertThat(byActor.path("items").get(0).path("actor").path("id").asString()).isEqualTo("ops.maker1");

    assertThat(actions(ok("/internal/v1/audit/events?actorType=user")))
        .containsExactly("txn.reversal.requested", "txn.reversal.approved");
    assertThat(actions(ok("/internal/v1/audit/events?action=txn.state.changed"))).containsExactly("txn.state.changed");
    assertThat(actions(ok("/internal/v1/audit/events?resource=txn/" + TXN_B)))
        .containsExactly("txn.reversal.approved", "txn.state.changed");
    assertThat(actions(ok("/internal/v1/audit/events?from=2026-11-03T08:30:00Z&to=2026-11-03T09:15:00Z")))
        .containsExactly("txn.reversal.approved");
    assertThat(actions(ok("/internal/v1/audit/events?limit=1&offset=1"))).containsExactly("txn.reversal.approved");
  }

  @Test
  void eventsOfSeveralMonthsAreSearchedTogether() throws Exception {
    record(TXN_A, "system", "mw-orchestrator", "txn.state.changed", "2026-11-03T08:15:31Z", "{}");
    clock.set(NOV.plus(Duration.ofDays(30)));
    record(TXN_A, "system", "mw-orchestrator", "txn.outcome.conflict", "2026-12-03T08:15:31Z", "{}");

    assertThat(actions(ok("/internal/v1/audit/events?txnId=" + TXN_A)))
        .containsExactly("txn.state.changed", "txn.outcome.conflict");
  }

  @Test
  void badFiltersAreTheValidationEnvelope() throws Exception {
    for (String q :
        List.of("txnId=nope", "limit=0", "limit=501", "from=yesterday",
            "from=2026-11-03T09:00:00Z&to=2026-11-03T08:00:00Z")) {
      HttpResponse<String> res = get("/internal/v1/audit/events?" + q);
      assertThat(res.statusCode()).as(q).isEqualTo(400);
      assertThat(json.readTree(res.body()).path("code").asString()).as(q).isEqualTo("MW-VAL-001");
    }
  }
}
