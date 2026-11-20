package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
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
 * Routing maker-checker (ticket 98): the maker cannot approve their own request, and an approval
 * publishes {@code mw.config.changed.v1}.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class RoutingRuleChangeRequestIT {

  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  RoutingRuleChangeRequestIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
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

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from routing_resource");
    jdbc.update("delete from change_request");
  }

  @Test
  void theMakerCannotApproveAndTheCheckerPublishesTheRule() {
    MvcTestResult proposed =
        mvc.post()
            .uri("/admin/v1/routing-rules/{id}/change-requests", "topup-gp-app")
            .with(MAKER)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"reason\":\"app connector\",\"value\":{\"txnType\":\"TOPUP\",\"operatorCode\":\"gp\","
                    + "\"channel\":\"CUSTOMER_APP\",\"primaryPartnerId\":\"ocs-app\",\"enabled\":true}}")
            .exchange();
    assertThat(proposed).hasStatus(201);
    String id = body(proposed).path("id").asString();

    assertThat(mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(MAKER).exchange()).hasStatus(403);

    assertThat(mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(CHECKER).exchange()).hasStatus(200);
    List<JsonNode> events = outbox();
    assertThat(events).hasSize(1);
    JsonNode event = events.get(0);
    assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(event)).isEmpty();
    assertThat(event.path("configType").asString()).isEqualTo("ROUTING_RULE");
    assertThat(event.path("configKey").asString()).isEqualTo("routing-rule/topup-gp-app");
    assertThat(event.path("change").asString()).isEqualTo("UPSERT");
    assertThat(event.path("version").asInt()).isEqualTo(1);
    assertThat(event.path("approvedBy").asString()).isEqualTo("ops-checker01");
  }

  @Test
  void prefixesOverridesAndMessagesPublishToo() {
    approve(
        "/admin/v1/msisdn-prefixes/016/change-requests",
        "{\"reason\":\"airtel\",\"value\":{\"mno\":\"airtel\",\"enabled\":true}}",
        "MSISDN_PREFIX",
        "msisdn-prefix/016");
    approve(
        "/admin/v1/mnp-overrides/01711000099/change-requests",
        "{\"reason\":\"ported\",\"value\":{\"mno\":\"robi\",\"enabled\":true}}",
        "MNP_OVERRIDE",
        "mnp-override/01711000099");
    approve(
        "/admin/v1/maintenance-messages/desco/change-requests",
        "{\"reason\":\"window\",\"value\":{\"billerCode\":\"DESCO\",\"messageEn\":\"DESCO is under maintenance\","
            + "\"messageBn\":\"ডেসকো রক্ষণাবেক্ষণে আছে\",\"enabled\":true}}",
        "MAINTENANCE_MESSAGE",
        "maintenance-message/desco");
  }

  private void approve(String uri, String content, String type, String key) {
    MvcTestResult proposed =
        mvc.post().uri(uri).with(MAKER).contentType(MediaType.APPLICATION_JSON).content(content).exchange();
    assertThat(proposed).hasStatus(201);
    String id = body(proposed).path("id").asString();
    assertThat(mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(CHECKER).exchange()).hasStatus(200);
    JsonNode event =
        outbox().stream().filter(e -> key.equals(e.path("configKey").asString())).findFirst().orElseThrow();
    assertThat(event.path("configType").asString()).isEqualTo(type);
    assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(event)).isEmpty();
  }

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private List<JsonNode> outbox() {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.config.changed.v1' order by id", String.class)
        .stream()
        .map(json::readTree)
        .toList();
  }
}
