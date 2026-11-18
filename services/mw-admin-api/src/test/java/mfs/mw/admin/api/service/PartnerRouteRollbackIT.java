package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.dto.PartnerRouteStatusMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The restart gap of ticket 83, closed in ticket 84 (ADR D26 amendment): an adapter that restarts
 * replays only the latest record per partner, so a REJECTED latest version would leave the partner
 * without its last good route. mw-admin-api therefore answers a REJECTED report for the partner's
 * current version by republishing the newest LIVE version's full config as the next version
 * (without a dry-run, so the adapter loads it at once), and the latest record is a good one again.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class PartnerRouteRollbackIT {

  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";
  static final RequestPostProcessor MAKER = operator("ops-maker01", "viewer", "ops-maker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");

  static final String ROUTE = """
      - route:
          from:
            uri: direct:partner-rollco
            steps:
              - to: "kamelet:mw-map?mapping=PAY.request"
              - to: "kamelet:mw-http-call?url=https://api.rollco.example&method=POST&path=/v1/payments&auth=apikey&secretField=api-key"
              - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
              - to: "kamelet:mw-audit"
      """;

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final PartnerRouteStatusService statuses;

  @Autowired
  PartnerRouteRollbackIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json, PartnerRouteStatusService statuses) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.statuses = statuses;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from change_request");
    jdbc.update("delete from partner.partner where id = 'rollco'");
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt().jwt(j -> j.subject("sub-" + username).issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of(roles))));
  }

  /** Asks for and approves an UPSERT_ROUTE of rollco with this route and timeout. */
  void approvedRoute(String route, long timeoutMs) {
    String body = json.writeValueAsString(Map.of(
        "action", "UPSERT_ROUTE",
        "reason", "rollco route",
        "family", "billpay",
        "config", Map.of(
            "route", route,
            "mappings", Map.of("PAY.request", "{\"ref\": reference}", "PAY.response", "{\"outcome\": \"UNKNOWN\"}"),
            "allowedHosts", List.of("api.rollco.example"),
            "timeoutMs", timeoutMs,
            "dryRun", Map.of("operation", "PAY"))));
    MvcTestResult asked = mvc.post().uri("/admin/v1/partners/rollco/change-requests").with(MAKER)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    assertThat(asked).hasStatus(201);
    String id = json.readTree(asked.getResponse().getContentAsByteArray()).path("id").asString();
    assertThat(mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(CHECKER).exchange()).hasStatus(200);
  }

  void report(long version, String status) {
    statuses.apply(new PartnerRouteStatusMessage(
        1, "rollco", version, "billpay", status,
        "REJECTED".equals(status) ? List.of("dry-run PAY gave no usable answer: HTTP_5XX") : List.of(),
        "LIVE".equals(status) ? List.of("mw-audit", "mw-classify", "mw-http-call", "mw-map") : List.of(),
        "mw-adapter-billpay", Instant.parse("2026-11-18T10:00:00Z")));
  }

  List<JsonNode> published() {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.config.changed.v1' order by id", String.class)
        .stream().map(json::readTree).toList();
  }

  long currentVersion() {
    return jdbc.queryForObject("select version from partner.partner where id = 'rollco'", Long.class);
  }

  @Test
  void aRejectedCurrentVersionRepublishesTheLastLiveVersionAsTheNextOne() {
    approvedRoute(ROUTE, 20000);
    report(1, "LIVE");
    approvedRoute(ROUTE.replace("definiteStatuses=422", "definiteStatuses=409"), 15000);
    assertThat(published()).hasSize(2);

    report(2, "REJECTED");

    List<JsonNode> records = published();
    assertThat(records).hasSize(3);
    JsonNode rollback = records.getLast();
    assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(rollback)).as("%s", rollback).isEmpty();
    assertThat(rollback.path("configKey").asString()).isEqualTo("partner/rollco");
    assertThat(rollback.path("version").asLong()).isEqualTo(3);
    assertThat(rollback.path("changedBy").asString()).isEqualTo("system/rollback");
    JsonNode value = rollback.path("value");
    // version 1's full config, without the dry-run: the adapter loads it at once
    assertThat(value.path("route").asString()).isEqualTo(ROUTE);
    assertThat(value.path("timeoutMs").asLong()).isEqualTo(20000);
    assertThat(value.path("family").asString()).isEqualTo("billpay");
    assertThat(value.has("dryRun")).isFalse();
    assertThat(currentVersion()).isEqualTo(3);
    assertThat(jdbc.queryForObject(
            "select timeout_ms from partner.partner where id = 'rollco'", Long.class)).isEqualTo(20000);
    assertThat(jdbc.queryForObject(
            "select route_status from partner.partner_route where partner_id = 'rollco' and version = 3", String.class))
        .isEqualTo("PENDING_DRY_RUN");
  }

  @Test
  void aRepeatedOrStaleRejectedReportPublishesNothingMore() {
    approvedRoute(ROUTE, 20000);
    report(1, "LIVE");
    approvedRoute(ROUTE.replace("definiteStatuses=422", "definiteStatuses=409"), 15000);
    report(2, "REJECTED");
    assertThat(published()).hasSize(3);

    // another instance of the adapter, or a replay after its restart, reports version 2 again
    report(2, "REJECTED");

    assertThat(published()).hasSize(3);
    assertThat(currentVersion()).isEqualTo(3);
  }

  @Test
  void aRejectedVersionWithoutALivePredecessorPublishesNothing() {
    approvedRoute(ROUTE, 20000);

    report(1, "REJECTED");

    assertThat(published()).hasSize(1);
    assertThat(currentVersion()).isEqualTo(1);
  }
}
