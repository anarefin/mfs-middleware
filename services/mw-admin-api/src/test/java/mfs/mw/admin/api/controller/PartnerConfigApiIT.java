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
 * (key {@code partner/<id>}, version + 1) to the outbox in the same transaction. The partner is the
 * dev seed {@code ocs} (the adapters' own dev seed), re-seeded before each test.
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
    assertThat(seeds.seedAll()).isEqualTo(1);
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
    // a full snapshot, not a diff: everything else is the seed's
    assertThat(value.path("baseUrl").asString()).isEqualTo("http://mw-simulators:8080");
    assertThat(value.path("timeoutMs").asLong()).isEqualTo(20000);
    assertThat(value.path("operations").path("PAY").path("path").asString()).isEqualTo("/ocs/v1/topup");
    assertThat(value.path("operations").path("STATUS_QUERY").path("definiteStatuses").toString()).isEqualTo("[200,404]");
    assertThat(value.path("errorMap").path("2001").path("suffix").asString()).isEqualTo("TOPUP_REJECTED");
    assertThat(value.path("ambiguousCodes").toString()).isEqualTo("[\"9876\"]");

    JsonNode after = partner("ocs");
    assertThat(after.path("version").asLong()).isEqualTo(1);
    assertThat(after.path("config").path("enabled").asBoolean()).isFalse();
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

  // --- onboarding -----------------------------------------------------------------------------

  @Test
  void anIntegrationMakerOnboardsAPartnerWithItsFullConfig() {
    String body =
        """
        {"action":"UPSERT","reason":"onboard robi","family":"telco","config":{
          "baseUrl":"https://robi.example.test","timeoutMs":15000,
          "auth":{"scheme":"api-key","credentialsRef":"api-key"},
          "operations":{"PAY":{"method":"POST","path":"/topup","definiteStatuses":[422],
            "request":"{\\"ref\\": reference}","response":"{\\"outcome\\": \\"UNKNOWN\\"}"}}}}
        """;
    String id = asked(INTEGRATION_MAKER, "robi", body);
    assertThat(approve(CHECKER, id)).hasStatus(200);

    JsonNode record = outbox(CONFIG_TOPIC).getFirst();
    assertEvent(CONFIG_SCHEMA, record);
    assertThat(record.path("configKey").asString()).isEqualTo("partner/robi");
    assertThat(record.path("version").asLong()).isEqualTo(1);
    assertThat(record.path("value").path("enabled").asBoolean()).isTrue();
    assertThat(record.path("value").path("auth").path("credentialsRef").asString()).isEqualTo("api-key");
    JsonNode robi = partner("robi");
    assertThat(robi.path("family").asString()).isEqualTo("telco");
    assertThat(body(mvc.get().uri("/admin/v1/partners").with(MAKER).exchange()).path("items")).hasSize(2);
  }

  @Test
  void anInvalidConfigIsRefusedWithoutEchoingIt() throws Exception {
    MvcTestResult noFamily =
        ask(MAKER, "robi",
            "{\"action\":\"UPSERT\",\"reason\":\"x\",\"config\":{\"baseUrl\":\"https://robi.example.test\","
                + "\"operations\":{\"PAY\":{\"method\":\"POST\",\"path\":\"/t\",\"response\":\"x\"}}}}");
    assertThat(noFamily).hasStatus(400);
    assertThat(body(noFamily).path("code").asString()).isEqualTo("MW-VAL-001");

    MvcTestResult badPath =
        ask(MAKER, "robi",
            "{\"action\":\"UPSERT\",\"reason\":\"x\",\"family\":\"telco\",\"config\":{\"baseUrl\":\"https://u:p@evil.test\","
                + "\"auth\":{\"scheme\":\"api-key\",\"credentialsRef\":\"../db-password\"},"
                + "\"operations\":{\"PAY\":{\"method\":\"POST\",\"path\":\"/a/../b\",\"response\":\"x\"}}}}");
    assertThat(badPath).hasStatus(400);
    String text = badPath.getResponse().getContentAsString();
    assertThat(text).contains("config.baseUrl", "config.operations.PAY.path", "config.auth.credentialsRef");
    assertThat(text).doesNotContain("evil.test", "db-password", "/a/../b");

    MvcTestResult noTimeouts = ask(MAKER, "ocs", "{\"action\":\"SET_TIMEOUTS\",\"reason\":\"x\"}");
    assertThat(noTimeouts).hasStatus(400);
    MvcTestResult unknownField = ask(MAKER, "ocs", "{\"action\":\"DISABLE\",\"reason\":\"x\",\"secret\":\"s\"}");
    assertThat(unknownField).hasStatus(400);
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
}
