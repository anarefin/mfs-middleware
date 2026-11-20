package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.scheduler.BillerSeedJob;
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
 * Biller catalogue maker-checker (ticket 113, FRS ADM-05): a second user approves, the approval
 * stores the biller and publishes {@code mw.config.changed.v1} BILLER; the dev seed loads 57
 * billers once.
 */
@SpringBootTest(
    properties = {
      "mw.outbox.relay.enabled=false",
      "mw.billers.seeds[0]=classpath:biller-seeds/dev/billers.csv"
    })
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class BillerChangeRequestIT {

  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";

  static final String DESCO =
      "{\"reason\":\"new biller\",\"value\":{\"nameEn\":\"Dhaka Electric Supply Company\",\"nameBn\":\"ডেসকো\","
          + "\"category\":\"UTILITY\",\"primaryAggregator\":\"billagg\",\"fallbackAggregator\":\"ekpay\","
          + "\"fields\":[{\"name\":\"accountNo\",\"labelEn\":\"Account number\",\"labelBn\":\"হিসাব নম্বর\","
          + "\"regex\":\"^[0-9]{8,12}$\",\"required\":true}],\"amountRule\":\"FIXED\",\"billMonthRequired\":true,"
          + "\"enabled\":true}}";

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final BillerSeedJob seeds;

  @Autowired
  BillerChangeRequestIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json, BillerSeedJob seeds) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.seeds = seeds;
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
    jdbc.update("delete from biller");
    jdbc.update("delete from change_request");
  }

  @Test
  void aSecondUserApprovesANewBillerAndItIsPublished() {
    MvcTestResult proposed = propose("DESCO", DESCO);
    assertThat(proposed).hasStatus(201);
    String id = body(proposed).path("id").asString();

    assertThat(mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(MAKER).exchange()).hasStatus(403);
    assertThat(mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(CHECKER).exchange()).hasStatus(200);

    JsonNode event = single("biller/DESCO");
    assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(event)).isEmpty();
    assertThat(event.path("configType").asString()).isEqualTo("BILLER");
    assertThat(event.path("version").asInt()).isEqualTo(1);
    assertThat(event.path("value").path("code").asString()).isEqualTo("DESCO");
    assertThat(event.path("approvedBy").asString()).isEqualTo("ops-checker01");

    JsonNode list = body(mvc.get().uri("/admin/v1/billers").with(CHECKER).exchange());
    assertThat(list.path("billers").get(0).path("code").asString()).isEqualTo("DESCO");
    assertThat(list.path("billers").get(0).path("version").asInt()).isEqualTo(1);
  }

  @Test
  void disablingABillerPublishesItWithItsMaintenanceText() {
    approve(propose("DESCO", DESCO));
    jdbc.update("delete from outbox");

    approve(
        propose(
            "DESCO",
            "{\"action\":\"DISABLE\",\"reason\":\"outage\",\"maintenanceEn\":\"DESCO is under maintenance\","
                + "\"maintenanceBn\":\"ডেসকো রক্ষণাবেক্ষণে আছে\"}"));

    JsonNode event = single("biller/DESCO");
    assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(event)).isEmpty();
    assertThat(event.path("version").asInt()).isEqualTo(2);
    assertThat(event.path("value").path("enabled").asBoolean()).isFalse();
    assertThat(event.path("value").path("maintenanceEn").asString()).isEqualTo("DESCO is under maintenance");
    assertThat(event.path("value").path("fields").size()).isEqualTo(1);
  }

  @Test
  void aBrokenRegexOrAmountRuleIsRefused() {
    assertThat(propose("DESCO", DESCO.replace("^[0-9]{8,12}$", "^[0-9{8"))).hasStatus(400);
    assertThat(propose("DESCO", DESCO.replace("\"FIXED\"", "\"OPEN\",\"minAmountMinor\":500,\"maxAmountMinor\":100")))
        .hasStatus(400);
    assertThat(propose("desco", DESCO)).hasStatus(400);
  }

  @Test
  void theDevSeedLoads57BillersOnce() {
    assertThat(seeds.seedAll()).isEqualTo(57);
    assertThat(jdbc.queryForObject("select count(*) from biller", Integer.class)).isEqualTo(57);
    assertThat(jdbc.queryForObject("select count(*) from biller where category = 'UTILITY'", Integer.class))
        .isEqualTo(17);
    List<JsonNode> events = outbox();
    assertThat(events).hasSize(57);
    events.forEach(e -> assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(e)).isEmpty());

    assertThat(seeds.seedAll()).isZero();
    assertThat(outbox()).hasSize(57);
  }

  private MvcTestResult propose(String code, String content) {
    return mvc.post()
        .uri("/admin/v1/billers/{code}/change-requests", code)
        .with(MAKER)
        .contentType(MediaType.APPLICATION_JSON)
        .content(content)
        .exchange();
  }

  private void approve(MvcTestResult proposed) {
    assertThat(proposed).hasStatus(201);
    String id = body(proposed).path("id").asString();
    assertThat(mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(CHECKER).exchange()).hasStatus(200);
  }

  private JsonNode single(String key) {
    List<JsonNode> events = outbox().stream().filter(e -> key.equals(e.path("configKey").asString())).toList();
    assertThat(events).hasSize(1);
    return events.get(0);
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
