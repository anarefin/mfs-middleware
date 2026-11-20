package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import mfs.mw.admin.api.scheduler.PartnerSeedJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Partner config through maker-checker (ticket 38, spec 15.3, 20.4): a maker asks to disable a
 * partner, change its timeouts or onboard one; a different checker approves; the approval updates
 * schema {@code partner} and writes the full config as a {@code mw.config.changed.v1} PARTNER record
 * (key {@code partner/<id>}, version + 1) to the outbox in the same transaction. The partners are
 * the dev seeds {@code ocs}, {@code billagg}, {@code c2wbank} (the adapters' own dev seeds, routes
 * since ticket 84), re-seeded before each test.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, PartnerConfigApiIT.TestClock.class})
class PartnerConfigApiIT {

  static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";
  static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  static final String CONFIG_TOPIC = "mw.config.changed.v1";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    Clock testClock() {
      return new MutableClock(NOW);
    }
  }

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final PartnerSeedJob seeds;

  @Autowired
  PartnerConfigApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json, PartnerSeedJob seeds) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.seeds = seeds;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
    jdbc.update("delete from change_request");
    jdbc.update("delete from partner.partner");
    assertThat(seeds.seedAll()).isEqualTo(7);
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt()
        .jwt(
            j ->
                j.subject("sub-" + username)
                    .issuer("http://keycloak:8080/realms/ops")
                    .claim("preferred_username", username)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = operator("ops-maker01", "viewer", "ops-maker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");
  static final RequestPostProcessor INTEGRATION_MAKER = operator("int-maker01", "integration-maker");
  static final RequestPostProcessor INTEGRATION_CHECKER = operator("int-checker01", "integration-checker");

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private MvcTestResult ask(RequestPostProcessor who, String partnerId, String body) {
    return mvc.post().uri("/admin/v1/partners/{id}/change-requests", partnerId).with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private String asked(RequestPostProcessor who, String partnerId, String body) {
    MvcTestResult r = ask(who, partnerId, body);
    assertThat(r).hasStatus(201);
    JsonNode cr = body(r);
    assertValid("/admin/v1/partners/{partnerId}/change-requests", "post", "201", cr);
    assertThat(cr.path("resourceType").asString()).isEqualTo("partner");
    assertThat(cr.path("status").asString()).isEqualTo("PENDING");
    return cr.path("id").asString();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private JsonNode partner(String id) {
    MvcTestResult r = mvc.get().uri("/admin/v1/partners/{id}", id).with(CHECKER).exchange();
    assertThat(r).hasStatus(200);
    JsonNode p = body(r);
    assertValid("/admin/v1/partners/{partnerId}", "get", "200", p);
    return p;
  }

  private List<JsonNode> outbox(String topic) {
    return jdbc.queryForList("select payload::text from outbox where topic = ? order by id", String.class, topic)
        .stream().map(json::readTree).toList();
  }

  private List<String> outboxKeys(String topic) {
    return jdbc.queryForList("select msg_key from outbox where topic = ? order by id", String.class, topic);
  }

  private static void assertValid(String path, String method, String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, path, method, status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  private static void assertEvent(String schema, JsonNode e) {
    assertThat(ContractSchemas.schema(schema).validate(e)).as("%s", e).isEmpty();
  }

  // --- disable / enable -----------------------------------------------------------------------

  @Test
  void anApprovedDisablePublishesTheFullConfigAsTheNextVersion() {
    JsonNode seed = partner("ocs");
    assertThat(seed.path("version").asLong()).isZero();
    assertThat(seed.path("config").path("enabled").asBoolean()).isTrue();

    String id = asked(MAKER, "ocs", "{\"action\":\"DISABLE\",\"reason\":\"OCS incident INC-42\"}");
    JsonNode pending = body(mvc.get().uri("/admin/v1/change-requests/{id}", id).with(CHECKER).exchange());
    assertThat(pending.path("diff").path("before").path("enabled").asBoolean()).isTrue();
    assertThat(pending.path("diff").path("after").path("enabled").asBoolean()).isFalse();
    assertThat(pending.path("payload").path("baseVersion").asLong()).isZero();
    assertThat(outbox(CONFIG_TOPIC)).as("nothing published before approval").isEmpty();

    MvcTestResult approved = approve(CHECKER, id);

    assertThat(approved).hasStatus(200);
    JsonNode cr = body(approved);
    assertValid("/admin/v1/change-requests/{id}/approve", "post", "200", cr);
    assertThat(cr.path("status").asString()).isEqualTo("APPROVED");
    assertThat(cr.path("payload").path("version").asLong()).isEqualTo(1);

    List<JsonNode> records = outbox(CONFIG_TOPIC);
    assertThat(records).hasSize(1);
    assertThat(outboxKeys(CONFIG_TOPIC)).containsExactly("partner/ocs");
    JsonNode record = records.getFirst();
    assertEvent(CONFIG_SCHEMA, record);
    assertThat(record.path("configKey").asString()).isEqualTo("partner/ocs");
    assertThat(record.path("configType").asString()).isEqualTo("PARTNER");
    assertThat(record.path("change").asString()).isEqualTo("UPSERT");
    assertThat(record.path("version").asLong()).isEqualTo(1);
    assertThat(record.path("changedBy").asString()).isEqualTo("ops-maker01");
    assertThat(record.path("approvedBy").asString()).isEqualTo("ops-checker01");
    JsonNode value = record.path("value");
    assertThat(value.path("enabled").asBoolean()).isFalse();
    // a full snapshot, not a diff: everything else is the seed's, its route included, and no
    // dry-run (the route already runs, so the adapter swaps the version in at once; ticket 84)
    assertThat(value.path("family").asString()).isEqualTo("telco");
    assertThat(value.path("route").asString())
        .contains("direct:partner-ocs", "url=http://mw-simulators:8080&method=POST&path=/ocs/v1/topup");
    assertThat(value.path("mappings").path("PAY.request").asString()).contains("creditParty.id");
    assertThat(value.path("allowedHosts").toString()).isEqualTo("[\"mw-simulators\"]");
    assertThat(value.has("dryRun")).isFalse();
    assertThat(value.has("template") || value.has("operations") || value.has("baseUrl")).isFalse();
    assertThat(value.path("timeoutMs").asLong()).isEqualTo(20000);
    assertThat(value.path("errorMap").path("2001").path("suffix").asString()).isEqualTo("TOPUP_REJECTED");
    assertThat(value.path("ambiguousCodes").toString()).isEqualTo("[\"9876\"]");

    JsonNode after = partner("ocs");
    assertThat(after.path("version").asLong()).isEqualTo(1);
    assertThat(after.path("config").path("enabled").asBoolean()).isFalse();
    assertThat(after.path("route").path("status").asString()).isEqualTo("PENDING_DRY_RUN");
    assertThat(after.path("approvedBy").asString()).isEqualTo("ops-checker01");

    List<JsonNode> audits =
        outbox("mw.audit.event.v1").stream()
            .filter(e -> e.path("action").asString().equals("admin.change_request.approved"))
            .toList();
    assertThat(audits).hasSize(1);
    assertEvent(AUDIT_SCHEMA, audits.getFirst());
    assertThat(audits.getFirst().path("resource").asString()).isEqualTo("partner/ocs");
    assertThat(audits.getFirst().has("txnId")).isFalse();
    assertThat(audits.getFirst().path("after").path("config").path("enabled").asBoolean()).isFalse();

    // re-enable: version 2
    String again = asked(MAKER, "ocs", "{\"action\":\"ENABLE\",\"reason\":\"OCS back\"}");
    assertThat(approve(INTEGRATION_CHECKER, again)).hasStatus(200);
    JsonNode second = outbox(CONFIG_TOPIC).getLast();
    assertThat(second.path("version").asLong()).isEqualTo(2);
    assertThat(second.path("value").path("enabled").asBoolean()).isTrue();
  }

  @Test
  void aLoweredTimeoutIsPublishedWithTheRestOfTheConfig() {
    String id = asked(MAKER, "ocs", "{\"action\":\"SET_TIMEOUTS\",\"reason\":\"fail faster\",\"timeouts\":{\"timeoutMs\":5000}}");
    assertThat(approve(CHECKER, id)).hasStatus(200);

    JsonNode value = outbox(CONFIG_TOPIC).getFirst().path("value");
    assertThat(value.path("timeoutMs").asLong()).isEqualTo(5000);
    assertThat(value.path("connectTimeoutMs").asLong()).isEqualTo(3000);
    assertThat(value.path("enabled").asBoolean()).isTrue();
    assertThat(partner("ocs").path("config").path("timeoutMs").asLong()).isEqualTo(5000);
  }

  // --- seeds ---------------------------------------------------------------------------------

  /**
   * Ticket 131b: switching maintenance or the endpoint profile is a maker-checker config change on
   * resourceType {@code partner}, with no route body. A body that also sends a route is refused.
   */
  @Test
  void aMaintenanceChangeAndAnEndpointChangeAreApprovedWithoutARoute() {
    String maintenance =
        """
        {"action":"SET_MAINTENANCE","reason":"planned window","config":{
          "maintenance":true,
          "maintenanceWindow":{"start":"2026-11-21T00:00:00Z","end":"2026-11-21T02:00:00Z",
            "messageEn":"Top-up is under maintenance","messageBn":"টপ-আপ রক্ষণাবেক্ষণে আছে"}}}
        """;
    assertThat(approve(CHECKER, asked(MAKER, "ocs", maintenance))).hasStatus(200);
    JsonNode value = outbox(CONFIG_TOPIC).getFirst().path("value");
    assertEvent(CONFIG_SCHEMA, outbox(CONFIG_TOPIC).getFirst());
    assertThat(value.path("maintenance").asBoolean()).isTrue();
    assertThat(value.path("maintenanceWindow").path("end").asString()).isEqualTo("2026-11-21T02:00:00Z");
    assertThat(value.path("maintenanceWindow").path("messageEn").asString()).isEqualTo("Top-up is under maintenance");
    assertThat(value.path("maintenanceWindow").path("messageBn").asString()).isEqualTo("টপ-আপ রক্ষণাবেক্ষণে আছে");
    assertThat(value.path("family").asString()).isEqualTo("telco");
    assertThat(value.path("route").asString()).contains("direct:partner-ocs");
    assertThat(value.has("dryRun")).isFalse();

    String endpoint =
        """
        {"action":"SET_ENDPOINT","reason":"switch to the mock","config":{
          "endpoint":"MOCK",
          "endpoints":{"MOCK":"http://mw-simulators:8080","REAL":"http://mw-simulators:8080"}}}
        """;
    assertThat(approve(CHECKER, asked(MAKER, "ocs", endpoint))).hasStatus(200);
    JsonNode next = outbox(CONFIG_TOPIC).getLast().path("value");
    assertThat(next.path("endpoint").asString()).isEqualTo("MOCK");
    assertThat(next.path("endpoints").path("REAL").asString()).isEqualTo("http://mw-simulators:8080");
    assertThat(next.path("maintenance").asBoolean()).as("the window stays").isTrue();
    assertThat(next.path("route").asString()).isEqualTo(value.path("route").asString());
    assertThat(partner("ocs").path("config").path("endpoint").asString()).isEqualTo("MOCK");

    for (String bad : List.of(
        """
        {"action":"SET_MAINTENANCE","reason":"x","config":{"maintenance":true,"route":"- route: {}"}}
        """,
        """
        {"action":"SET_ENDPOINT","reason":"x","family":"telco","config":{
          "endpoint":"REAL","endpoints":{"MOCK":"http://mw-simulators:8080","REAL":"http://mw-simulators:8080"},
          "route":"- route: {}","mappings":{"PAY.request":"{}"}}}
        """)) {
      MvcTestResult refused = ask(MAKER, "ocs", bad);
      assertThat(refused).hasStatus(400);
      assertThat(body(refused).path("code").asString()).isEqualTo("MW-VAL-001");
      assertThat(body(refused).path("details").toString()).contains("config.route");
    }
  }

  @Test
  void theDevSeedsAreTheAdaptersPartnersAsRoutes() {
    JsonNode items = body(mvc.get().uri("/admin/v1/partners").with(CHECKER).exchange()).path("items");
    assertValid("/admin/v1/partners", "get", "200", body(mvc.get().uri("/admin/v1/partners").with(CHECKER).exchange()));
    assertThat(items).hasSize(7);
    Map<String, String> families = Map.of(
        "billagg", "billpay", "c2wbank", "bank", "ocs", "telco",
        "gp-ocs", "telco", "robi-ocs", "telco", "teletalk-ocs", "telco", "bl-ocs", "telco");
    for (JsonNode p : items) {
      String id = p.path("partnerId").asString();
      assertThat(p.path("family").asString()).isEqualTo(families.get(id));
      assertThat(p.path("version").asLong()).isZero();
      assertThat(p.path("config").path("route").asString()).contains("uri: direct:partner-" + id, "kamelet:mw-audit");
      assertThat(p.path("config").path("allowedHosts").toString()).isEqualTo("[\"mw-simulators\"]");
      assertThat(p.has("route")).as("a seed has no approved version to report on").isFalse();
    }
    assertThat(partner("c2wbank").path("config").path("mappings").has("CALLBACK.response")).isTrue();
  }

  @Test
  void aBillaggChangeIsAcceptedNow() {
    String id = asked(MAKER, "billagg", "{\"action\":\"DISABLE\",\"reason\":\"aggregator incident\"}");
    assertThat(approve(CHECKER, id)).hasStatus(200);
    JsonNode value = outbox(CONFIG_TOPIC).getFirst().path("value");
    assertThat(value.path("family").asString()).isEqualTo("billpay");
    assertThat(value.path("enabled").asBoolean()).isFalse();
  }

  // --- ticket 97 / 131b fields: never dropped by a republish ----------------------------------

  @Test
  void theHealthMaintenanceAndEndpointFieldsSurviveEveryRepublish() {
    String body =
        """
        {"action":"UPSERT_ROUTE","reason":"onboard mtco","family":"telco","config":{
          "allowedHosts":["mock.mtco.example.test","api.mtco.example.test"],"dryRun":{"operation":"PAY"},
          "maintenance":true,"healthCheck":true,
          "maintenanceWindow":{"start":"2026-11-07T00:00:00Z","end":"2026-11-07T02:00:00Z",
            "messageEn":"MTCO top-up is under maintenance","messageBn":"এমটিকো টপ-আপ রক্ষণাবেক্ষণে আছে"},
          "endpoint":"MOCK",
          "endpoints":{"MOCK":"http://mock.mtco.example.test:8080","REAL":"https://api.mtco.example.test"},
          "route":"- route:\\n    from:\\n      uri: direct:partner-mtco\\n      steps:\\n        - to: \\"kamelet:mw-map?mapping=PAY.request\\"\\n        - to: \\"kamelet:mw-http-call?url={{endpoint}}&method=POST&path=/topup\\"\\n        - to: \\"kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422\\"\\n        - to: \\"kamelet:mw-audit\\"\\n",
          "mappings":{"PAY.request":"{\\"ref\\": reference}","PAY.response":"{\\"outcome\\": \\"UNKNOWN\\"}"}}}
        """;
    assertThat(approve(CHECKER, asked(INTEGRATION_MAKER, "mtco", body))).hasStatus(200);
    assertThat(approve(CHECKER, asked(MAKER, "mtco", "{\"action\":\"SET_TIMEOUTS\",\"reason\":\"x\",\"timeouts\":{\"timeoutMs\":9000}}")))
        .hasStatus(200);

    JsonNode record = outbox(CONFIG_TOPIC).getLast();
    assertEvent(CONFIG_SCHEMA, record);
    for (JsonNode config : List.of(record.path("value"), partner("mtco").path("config"))) {
      assertThat(config.path("timeoutMs").asLong()).isEqualTo(9000);
      assertThat(config.path("maintenance").asBoolean()).as("%s", config).isTrue();
      assertThat(config.path("healthCheck").asBoolean()).isTrue();
      assertThat(config.path("maintenanceWindow").path("end").asString()).isEqualTo("2026-11-07T02:00:00Z");
      assertThat(config.path("maintenanceWindow").path("messageBn").asString()).isEqualTo("এমটিকো টপ-আপ রক্ষণাবেক্ষণে আছে");
      assertThat(config.path("endpoint").asString()).isEqualTo("MOCK");
      assertThat(config.path("endpoints").path("REAL").asString()).isEqualTo("https://api.mtco.example.test");
    }
  }

  // --- onboarding -----------------------------------------------------------------------------

  @Test
  void anIntegrationMakerOnboardsAPartnerWithItsFullConfig() {
    String body =
        """
        {"action":"UPSERT_ROUTE","reason":"onboard robi","family":"telco","config":{
          "timeoutMs":15000,"allowedHosts":["robi.example.test"],"dryRun":{"operation":"PAY"},
          "route":"- route:\\n    from:\\n      uri: direct:partner-robi\\n      steps:\\n        - to: \\"kamelet:mw-map?mapping=PAY.request\\"\\n        - to: \\"kamelet:mw-http-call?url=https://robi.example.test&method=POST&path=/topup&auth=apikey&secretField=api-key\\"\\n        - to: \\"kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422\\"\\n        - to: \\"kamelet:mw-audit\\"\\n",
          "mappings":{"PAY.request":"{\\"ref\\": reference}","PAY.response":"{\\"outcome\\": \\"UNKNOWN\\"}"}}}
        """;
    String id = asked(INTEGRATION_MAKER, "robi", body);
    assertThat(approve(CHECKER, id)).hasStatus(200);

    JsonNode record = outbox(CONFIG_TOPIC).getFirst();
    assertEvent(CONFIG_SCHEMA, record);
    assertThat(record.path("configKey").asString()).isEqualTo("partner/robi");
    assertThat(record.path("version").asLong()).isEqualTo(1);
    assertThat(record.path("value").path("enabled").asBoolean()).isTrue();
    assertThat(record.path("value").path("dryRun").path("operation").asString()).isEqualTo("PAY");
    JsonNode robi = partner("robi");
    assertThat(robi.path("family").asString()).isEqualTo("telco");
    assertThat(body(mvc.get().uri("/admin/v1/partners").with(MAKER).exchange()).path("items")).hasSize(8);

    // version 1 has not passed its dry-run yet: an ENABLE / DISABLE / SET_TIMEOUTS on it keeps the
    // dry-run, so the unproven route still has to prove itself before the adapter loads it
    for (String action : List.of(
        "{\"action\":\"DISABLE\",\"reason\":\"x\"}",
        "{\"action\":\"SET_TIMEOUTS\",\"reason\":\"x\",\"timeouts\":{\"timeoutMs\":9000}}")) {
      String change = asked(MAKER, "robi", action);
      assertThat(approve(CHECKER, change)).hasStatus(200);
      JsonNode next = outbox(CONFIG_TOPIC).getLast().path("value");
      assertThat(next.path("dryRun").path("operation").asString()).as(action).isEqualTo("PAY");
    }
    assertThat(partner("robi").path("route").path("status").asString()).isEqualTo("PENDING_DRY_RUN");
  }

  @Test
  void anActionOnALiveRouteDropsItsDryRun() {
    String body =
        """
        {"action":"UPSERT_ROUTE","reason":"onboard robi","family":"telco","config":{
          "timeoutMs":15000,"allowedHosts":["robi.example.test"],"dryRun":{"operation":"PAY"},
          "route":"- route:\\n    from:\\n      uri: direct:partner-robi\\n      steps:\\n        - to: \\"kamelet:mw-map?mapping=PAY.request\\"\\n        - to: \\"kamelet:mw-http-call?url=https://robi.example.test&method=POST&path=/topup&auth=apikey&secretField=api-key\\"\\n        - to: \\"kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422\\"\\n        - to: \\"kamelet:mw-audit\\"\\n",
          "mappings":{"PAY.request":"{\\"ref\\": reference}","PAY.response":"{\\"outcome\\": \\"UNKNOWN\\"}"}}}
        """;
    assertThat(approve(CHECKER, asked(INTEGRATION_MAKER, "robi", body))).hasStatus(200);
    jdbc.update("update partner.partner_route set route_status = 'LIVE' where partner_id = 'robi' and version = 1");

    assertThat(approve(CHECKER, asked(MAKER, "robi", "{\"action\":\"DISABLE\",\"reason\":\"x\"}"))).hasStatus(200);

    JsonNode next = outbox(CONFIG_TOPIC).getLast().path("value");
    assertThat(next.path("enabled").asBoolean()).isFalse();
    assertThat(next.has("dryRun")).as("a proven route loads at once").isFalse();
  }

  @Test
  void anInvalidConfigIsRefusedWithoutEchoingIt() throws Exception {
    MvcTestResult noFamily =
        ask(MAKER, "robi",
            "{\"action\":\"UPSERT_ROUTE\",\"reason\":\"x\",\"config\":{\"route\":\"- route: {}\","
                + "\"allowedHosts\":[\"robi.example.test\"],\"dryRun\":{\"operation\":\"PAY\"}}}");
    assertThat(noFamily).hasStatus(400);
    assertThat(body(noFamily).path("code").asString()).isEqualTo("MW-VAL-001");

    MvcTestResult badRef =
        ask(MAKER, "robi",
            "{\"action\":\"UPSERT_ROUTE\",\"reason\":\"x\",\"family\":\"telco\",\"config\":{\"route\":\"- route: {}\","
                + "\"allowedHosts\":[\"robi.example.test\"],\"auth\":{\"scheme\":\"api-key\",\"credentialsRef\":\"../db-password\"},"
                + "\"dryRun\":{\"operation\":\"PAY\",\"baseUrl\":\"https://evil.test\"}}}");
    assertThat(badRef).hasStatus(400);
    String text = badRef.getResponse().getContentAsString();
    assertThat(text).contains("config.auth.credentialsRef", "config.dryRun.baseUrl");
    assertThat(text).doesNotContain("evil.test", "db-password");

    MvcTestResult noTimeouts = ask(MAKER, "ocs", "{\"action\":\"SET_TIMEOUTS\",\"reason\":\"x\"}");
    assertThat(noTimeouts).hasStatus(400);
    MvcTestResult unknownField = ask(MAKER, "ocs", "{\"action\":\"DISABLE\",\"reason\":\"x\",\"secret\":\"s\"}");
    assertThat(unknownField).hasStatus(400);
    assertThat(jdbc.queryForObject("select count(*) from change_request", Integer.class)).isZero();
  }

  /** The old template shape and its action went with ticket 84: the contract refuses them. */
  @Test
  void theOldUpsertActionAndTemplateFieldsAreRefused() {
    MvcTestResult upsert =
        ask(MAKER, "robi",
            "{\"action\":\"UPSERT\",\"reason\":\"x\",\"family\":\"telco\",\"config\":{\"timeoutMs\":1000}}");
    assertThat(upsert).hasStatus(400);
    assertThat(body(upsert).path("code").asString()).isEqualTo("MW-VAL-001");

    MvcTestResult template =
        ask(MAKER, "robi",
            "{\"action\":\"UPSERT_ROUTE\",\"reason\":\"x\",\"family\":\"telco\",\"config\":{"
                + "\"template\":\"rest-json-request-reply\",\"baseUrl\":\"https://robi.example.test\","
                + "\"operations\":{\"PAY\":{\"method\":\"POST\",\"path\":\"/t\",\"response\":\"x\"}}}}");
    assertThat(template).hasStatus(400);
    assertThat(body(template).path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(jdbc.queryForObject("select count(*) from change_request", Integer.class)).isZero();
  }

  // --- refusals -------------------------------------------------------------------------------

  @Test
  void aChangeThatChangesNothingOrAnUnknownPartnerIsRefused() {
    MvcTestResult nothing = ask(MAKER, "ocs", "{\"action\":\"ENABLE\",\"reason\":\"x\"}");
    assertThat(nothing).hasStatus(409);
    assertThat(body(nothing).path("code").asString()).isEqualTo("MW-VAL-030");

    MvcTestResult unknown = ask(MAKER, "gp", "{\"action\":\"DISABLE\",\"reason\":\"x\"}");
    assertThat(unknown).hasStatus(404);
    assertThat(body(unknown).path("code").asString()).isEqualTo("MW-VAL-003");

    asked(MAKER, "ocs", "{\"action\":\"DISABLE\",\"reason\":\"x\"}");
    MvcTestResult second = ask(MAKER, "ocs", "{\"action\":\"SET_TIMEOUTS\",\"reason\":\"x\",\"timeouts\":{\"timeoutMs\":1000}}");
    assertThat(second).hasStatus(409);
    assertThat(body(second).path("code").asString()).isEqualTo("MW-VAL-031");
  }

  @Test
  void onlyPartnerMakersAskAndOnlyAnotherPartnerCheckerApproves() {
    for (RequestPostProcessor notMaker :
        List.of(CHECKER, operator("viewer01", "viewer"), operator("recon01", "recon-maker"))) {
      assertThat(ask(notMaker, "ocs", "{\"action\":\"DISABLE\",\"reason\":\"x\"}")).hasStatus(403);
    }
    RequestPostProcessor both = operator("ops-both01", "ops-maker", "ops-checker");
    String id = asked(both, "ocs", "{\"action\":\"DISABLE\",\"reason\":\"x\"}");

    MvcTestResult own = approve(both, id);
    assertThat(own).hasStatus(403);
    assertThat(body(own).path("code").asString()).isEqualTo("MW-AUTH-030");
    assertThat(approve(MAKER, id)).hasStatus(403);
    assertThat(outbox(CONFIG_TOPIC)).isEmpty();
    assertThat(partner("ocs").path("version").asLong()).isZero();
  }

  @Test
  void aRejectedRequestPublishesNothing() {
    String id = asked(MAKER, "ocs", "{\"action\":\"DISABLE\",\"reason\":\"x\"}");
    MvcTestResult r =
        mvc.post().uri("/admin/v1/change-requests/{id}/reject", id).with(CHECKER)
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not now\"}").exchange();
    assertThat(r).hasStatus(200);
    assertThat(outbox(CONFIG_TOPIC)).isEmpty();
    assertThat(partner("ocs").path("config").path("enabled").asBoolean()).isTrue();
    JsonNode page =
        body(mvc.get().uri("/admin/v1/change-requests?resourceType=partner&status=REJECTED").with(CHECKER).exchange());
    assertValid("/admin/v1/change-requests", "get", "200", page);
    assertThat(page.path("items")).hasSize(1);
  }

  // --- error map (ticket 131, FRS ADM-08) ----------------------------------------------------

  private MvcTestResult askErrorMap(RequestPostProcessor who, String partnerId, String body) {
    return mvc.post().uri("/admin/v1/partners/{id}/error-map/change-requests", partnerId).with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  static final String ERROR_MAP =
      "{\"action\":\"SET_ERROR_MAP\",\"reason\":\"map OCS codes\",\"errorMap\":{\"E101\":{\"suffix\":\"INVALID_MSISDN\"},"
          + "\"E503\":{\"suffix\":\"BUSY\",\"retryable\":true}},\"ambiguousCodes\":[\"E999\"]}";

  @Test
  void anApprovedErrorMapIsPublishedWithTheRestOfTheConfigAsTheNextVersion() {
    MvcTestResult r = askErrorMap(INTEGRATION_MAKER, "ocs", ERROR_MAP);
    assertThat(r).hasStatus(201);
    JsonNode cr = body(r);
    assertValid("/admin/v1/partners/{partnerId}/error-map/change-requests", "post", "201", cr);
    assertThat(cr.path("resourceType").asString()).isEqualTo("partner_error_map");
    assertThat(cr.path("action").asString()).isEqualTo("SET_ERROR_MAP");
    assertThat(cr.path("diff").path("after").path("errorMap").path("E503").path("suffix").asString()).isEqualTo("BUSY");
    assertThat(outbox(CONFIG_TOPIC)).isEmpty();

    assertThat(approve(INTEGRATION_MAKER, cr.path("id").asString())).hasStatus(403);
    assertThat(approve(INTEGRATION_CHECKER, cr.path("id").asString())).hasStatus(200);
    JsonNode event = outbox(CONFIG_TOPIC).getFirst();
    assertEvent(CONFIG_SCHEMA, event);
    assertThat(event.path("version").asLong()).isEqualTo(1);
    JsonNode value = event.path("value");
    assertThat(value.path("errorMap").path("E101").path("suffix").asString()).isEqualTo("INVALID_MSISDN");
    assertThat(value.path("errorMap").path("E503").path("retryable").asBoolean()).isTrue();
    assertThat(value.path("ambiguousCodes").get(0).asString()).isEqualTo("E999");
    assertThat(value.path("route").asString()).contains("direct:partner-ocs");
    assertThat(value.has("dryRun")).isFalse();
    assertThat(partner("ocs").path("config").path("errorMap").path("E101").path("suffix").asString())
        .isEqualTo("INVALID_MSISDN");
    JsonNode audit = outbox("mw.audit.event.v1").getLast();
    assertEvent(AUDIT_SCHEMA, audit);
    assertThat(audit.path("resource").asString()).isEqualTo("partner_error_map/ocs");
  }

  @Test
  void aBadSuffixAnUnknownPartnerTheWrongRoleOrNoChangeAreRefused() {
    MvcTestResult bad = askErrorMap(MAKER, "ocs",
        "{\"action\":\"SET_ERROR_MAP\",\"reason\":\"x\",\"errorMap\":{\"E1\":{\"suffix\":\"lower case\"}}}");
    assertThat(bad).hasStatus(400);
    assertThat(body(bad).path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(askErrorMap(MAKER, "nope", ERROR_MAP)).hasStatus(404);
    assertThat(askErrorMap(operator("viewer01", "viewer"), "ocs", ERROR_MAP)).hasStatus(403);
    assertThat(askErrorMap(CHECKER, "ocs", ERROR_MAP)).hasStatus(403);
    String held = partner("ocs").path("config").path("errorMap").toString();
    MvcTestResult same = askErrorMap(MAKER, "ocs",
        "{\"action\":\"SET_ERROR_MAP\",\"reason\":\"x\",\"errorMap\":" + held + "}");
    assertThat(same).hasStatus(409);
  }
}
