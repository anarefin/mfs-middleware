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
 * Notification rules through maker-checker (ticket 133c, FRS NOT-01): an ops-maker proposes a rule
 * (event type + outcome + customer type to channels and template), checked at once and stored
 * PENDING; a different ops-checker approves it, which puts the full rule on {@code
 * mw.config.changed.v1} (configType NOTIFY_RULE, key {@code notify-rule/<ruleId>}) through the
 * outbox. {@code GET /admin/v1/notify-rules} lists the live version of each rule.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, NotifyRuleChangeApiIT.TestClock.class})
class NotifyRuleChangeApiIT {

  static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";

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

  @Autowired
  NotifyRuleChangeApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
    jdbc.update("delete from change_request");
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt().jwt(j -> j.subject("sub-" + username).issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username).claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = operator("ops-maker01", "viewer", "ops-maker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");

  static String rule(String channels) {
    return """
        {"eventType":"P2P","outcome":"SUCCESS","customerType":"CUSTOMER","recipient":"DEBIT_PARTY",
         "channels":%s,"templateCode":"TXN_P2P_SENT","reason":"receipt by push too"}
        """.formatted(channels);
  }

  private MvcTestResult propose(RequestPostProcessor who, String ruleId, String body) {
    return mvc.post().uri("/admin/v1/notify-rules/{id}/change-requests", ruleId).with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private List<JsonNode> configEvents() {
    return jdbc.queryForList("select payload::text from outbox where topic = 'mw.config.changed.v1' order by id",
        String.class).stream().map(json::readTree).toList();
  }

  private static void assertResponse(String path, String method, String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, path, method, status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  @Test
  void anApprovedRuleIsPublishedAndListedWithItsNewChannelSet() {
    MvcTestResult created = propose(MAKER, "p2p-success-receipt", rule("[\"SMS\"]"));
    assertThat(created).hasStatus(201);
    assertResponse("/admin/v1/notify-rules/{ruleId}/change-requests", "post", "201", body(created));
    assertThat(body(created).path("resourceType").asString()).isEqualTo("notify-rule");
    assertThat(configEvents()).as("nothing before approval").isEmpty();
    assertThat(approve(CHECKER, body(created).path("id").asString())).hasStatus(200);

    String second = body(propose(MAKER, "p2p-success-receipt", rule("[\"SMS\",\"PUSH\"]"))).path("id").asString();
    assertThat(approve(CHECKER, second)).hasStatus(200);

    List<JsonNode> events = configEvents();
    assertThat(events).hasSize(2);
    JsonNode event = events.get(1);
    assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(event)).as("%s", event).isEmpty();
    assertThat(event.path("configKey").asString()).isEqualTo("notify-rule/p2p-success-receipt");
    assertThat(event.path("configType").asString()).isEqualTo("NOTIFY_RULE");
    assertThat(event.path("version").asInt()).isEqualTo(2);
    assertThat(event.path("approvedBy").asString()).isEqualTo("ops-checker01");
    JsonNode value = event.path("value");
    assertThat(value.path("ruleId").asString()).isEqualTo("p2p-success-receipt");
    assertThat(value.path("channels").valueStream().map(JsonNode::asString).toList()).containsExactly("SMS", "PUSH");
    assertThat(value.path("enabled").asBoolean()).as("enabled by default").isTrue();
    assertThat(value.has("validFrom")).isFalse();

    MvcTestResult list = mvc.get().uri("/admin/v1/notify-rules").with(operator("viewer01", "viewer")).exchange();
    assertThat(list).hasStatus(200);
    assertResponse("/admin/v1/notify-rules", "get", "200", body(list));
    assertThat(body(list).path("items")).singleElement().satisfies(i -> {
      assertThat(i.path("ruleId").asString()).isEqualTo("p2p-success-receipt");
      assertThat(i.path("version").asInt()).isEqualTo(2);
      assertThat(i.path("channels").valueStream().map(JsonNode::asString).toList()).containsExactly("SMS", "PUSH");
    });
  }

  @Test
  void anUnusableRuleIsRefusedAtMakerTimeAndOnlyAMakerProposes() {
    MvcTestResult bad = propose(MAKER, "p2p-success-receipt",
        """
        {"eventType":"p2p","outcome":"SUCCESS","recipient":"DEBIT_PARTY","channels":["SMS","SMS"],
         "templateCode":"TXN_P2P_SENT","reason":"x"}
        """);
    assertThat(bad).hasStatus(400);
    assertThat(body(bad).path("code").asString()).isEqualTo("MW-VAL-040");
    assertThat(body(bad).path("details").valueStream().map(d -> d.path("field").asString()).toList())
        .contains("eventType", "channels");

    assertThat(propose(MAKER, "Bad_Id", rule("[\"SMS\"]"))).hasStatus(400);
    MvcTestResult unknownChannel = propose(MAKER, "p2p-success-receipt", rule("[\"FAX\"]"));
    assertThat(unknownChannel).hasStatus(400);
    assertThat(body(unknownChannel).path("code").asString()).isEqualTo("MW-VAL-001");

    assertThat(propose(CHECKER, "p2p-success-receipt", rule("[\"SMS\"]"))).hasStatus(403);
    assertThat(jdbc.queryForObject("select count(*) from change_request", Integer.class)).isZero();
  }
}
