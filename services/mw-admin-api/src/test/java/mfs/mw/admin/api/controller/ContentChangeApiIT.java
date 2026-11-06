package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import mfs.mw.admin.api.scheduler.ChangeRequestExpiryJob;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * USSD menus and notify templates through maker-checker (ticket 39, spec 15.2, 15.3): an ops-maker
 * proposes a version, which is checked at once (a dangling node or a missing bn/en text is refused
 * at maker time, acceptance criterion 3) and stored PENDING; a different ops-checker approves it,
 * which in one transaction marks it PUBLISHED, audits it and puts the full version on {@code
 * mw.config.changed.v1} through the outbox; a rejection or expiry publishes nothing.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ContentChangeApiIT.TestClock.class})
class ContentChangeApiIT {

  static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";
  static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

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
  private final MutableClock clock;
  private final ChangeRequestExpiryJob expiry;

  @Autowired
  ContentChangeApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json, Clock clock, ChangeRequestExpiryJob expiry) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.clock = (MutableClock) clock;
    this.expiry = expiry;
  }

  @BeforeEach
  void reset() {
    clock.set(NOW);
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
    jdbc.update("delete from change_request");
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

  /** The shipped main menu as a maker sends it: the tree without menuId and version. */
  private ObjectNode seedTree() throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("menus/main-v2.json")) {
      ObjectNode tree = (ObjectNode) json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      tree.remove("menuId");
      tree.remove("version");
      return tree;
    }
  }

  private String menuBody(ObjectNode tree, String activations) {
    return "{\"menu\":" + json.writeValueAsString(tree) + ",\"activations\":" + activations + ",\"reason\":\"new wording\"}";
  }

  private MvcTestResult proposeMenu(RequestPostProcessor who, String menuId, String body) {
    return mvc.post().uri("/admin/v1/ussd-menus/{id}/change-requests", menuId).with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private MvcTestResult proposeTemplate(RequestPostProcessor who, String path, String body) {
    return mvc.post().uri("/admin/v1/notify-templates/" + path + "/change-requests").with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private MvcTestResult reject(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/reject", id).with(who)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wording not agreed\"}").exchange();
  }

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private List<JsonNode> outbox(String topic) {
    return jdbc.queryForList("select payload::text from outbox where topic = ? order by id", String.class, topic)
        .stream().map(json::readTree).toList();
  }

  private List<String> outboxKeys(String topic) {
    return jdbc.queryForList("select msg_key from outbox where topic = ? order by id", String.class, topic);
  }

  private List<JsonNode> audits(String action) {
    return outbox("mw.audit.event.v1").stream().filter(e -> e.path("action").asString().equals(action)).toList();
  }

  private static void assertResponse(String path, String method, String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, path, method, status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  private static void assertEvent(String schema, JsonNode e) {
    assertThat(ContractSchemas.schema(schema).validate(e)).as("%s", e).isEmpty();
  }

  // --- USSD menus -------------------------------------------------------------------------------

  @Test
  void anApprovedMenuVersionIsPublishedWithItsGoLivePerMno() throws IOException {
    ObjectNode tree = seedTree();
    ((ObjectNode) tree.path("nodes").path("main").path("text")).put("en", "upay new");
    String later = NOW.plus(Duration.ofHours(2)).toString();
    MvcTestResult created =
        proposeMenu(MAKER, "main", menuBody(tree, "[{\"mno\":\"robi\"},{\"mno\":\"gp\",\"goLiveAt\":\"" + later + "\"}]"));

    assertThat(created).hasStatus(201);
    JsonNode cr = body(created);
    assertResponse("/admin/v1/ussd-menus/{menuId}/change-requests", "post", "201", cr);
    assertThat(cr.path("status").asString()).isEqualTo("PENDING");
    assertThat(cr.path("resourceType").asString()).isEqualTo("ussd_menu");
    assertThat(cr.path("diff").path("after").path("version").asInt()).isEqualTo(1);
    assertThat(outbox("mw.config.changed.v1")).as("nothing published before approval").isEmpty();
    assertThat(audits("admin.change_request.created")).singleElement().satisfies(a -> assertEvent(AUDIT_SCHEMA, a));

    clock.advance(Duration.ofMinutes(5));
    MvcTestResult approved = approve(CHECKER, cr.path("id").asString());

    assertThat(approved).hasStatus(200);
    assertThat(body(approved).path("status").asString()).isEqualTo("APPROVED");
    JsonNode event = outbox("mw.config.changed.v1").getFirst();
    assertEvent(CONFIG_SCHEMA, event);
    assertThat(outboxKeys("mw.config.changed.v1")).containsExactly("ussd-menu/main");
    assertThat(event.path("configType").asString()).isEqualTo("USSD_MENU");
    assertThat(event.path("version").asInt()).isEqualTo(1);
    assertThat(event.path("changedBy").asString()).isEqualTo("ops-maker01");
    assertThat(event.path("approvedBy").asString()).isEqualTo("ops-checker01");
    JsonNode value = event.path("value");
    assertThat(value.path("menu").path("menuId").asString()).isEqualTo("main");
    assertThat(value.path("menu").path("nodes").path("main").path("text").path("en").asString()).isEqualTo("upay new");
    assertThat(value.path("activations").get(0).path("goLiveAt").asString())
        .as("no go-live: the approval time").isEqualTo(NOW.plus(Duration.ofMinutes(5)).toString());
    assertThat(value.path("activations").get(1).path("goLiveAt").asString()).isEqualTo(later);
    JsonNode audit = audits("admin.change_request.approved").getFirst();
    assertEvent(AUDIT_SCHEMA, audit);
    assertThat(audit.path("resource").asString()).isEqualTo("ussd_menu/main");
    assertThat(audit.has("txnId")).isFalse();
    assertThat(audit.path("after").path("config").path("version").asInt()).isEqualTo(1);

    MvcTestResult versions = mvc.get().uri("/admin/v1/ussd-menus/main/versions").with(CHECKER).exchange();
    assertThat(versions).hasStatus(200);
    assertResponse("/admin/v1/ussd-menus/{menuId}/versions", "get", "200", body(versions));
    assertThat(body(versions).path("items").get(0).path("status").asString()).isEqualTo("PUBLISHED");
    MvcTestResult one = mvc.get().uri("/admin/v1/ussd-menus/main/versions/1").with(operator("viewer01", "viewer")).exchange();
    assertThat(one).hasStatus(200);
    assertResponse("/admin/v1/ussd-menus/{menuId}/versions/{version}", "get", "200", body(one));
    assertThat(body(one).path("value").path("activations").get(0).path("goLiveAt").asString())
        .isEqualTo(NOW.plus(Duration.ofMinutes(5)).toString());
  }

  @Test
  void anInvalidMenuTreeIsRefusedAtMakerTimeAndNothingIsStored() throws IOException {
    ObjectNode tree = seedTree();
    ((ObjectNode) tree.path("nodes").path("p2p.recipient")).put("next", "p2p.nowhere");
    ((ObjectNode) tree.path("nodes").path("cancelled").path("text")).remove("en");

    MvcTestResult r = proposeMenu(MAKER, "main", menuBody(tree, "[{\"mno\":\"robi\"}]"));

    assertThat(r).hasStatus(400);
    JsonNode e = body(r);
    assertThat(e.path("code").asString()).isEqualTo("MW-VAL-040");
    assertThat(e.path("details").valueStream().map(d -> d.path("issue").asString()).toList())
        .contains("p2p.recipient: next points to missing node p2p.nowhere", "cancelled: text needs both bn and en");
    assertThat(jdbc.queryForObject("select count(*) from change_request", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from content_version", Integer.class)).isZero();
    assertThat(outbox("mw.audit.event.v1")).isEmpty();
  }

  @Test
  void contentRequestsCanBeListedByResourceType() throws IOException {
    assertThat(proposeMenu(MAKER, "main", menuBody(seedTree(), "[{\"mno\":\"robi\"}]"))).hasStatus(201);
    assertThat(proposeTemplate(MAKER, "TXN_P2P_RECEIVED/SMS/en", "{\"body\":\"Tk {{amount}}\",\"reason\":\"shorter\"}"))
        .hasStatus(201);

    MvcTestResult menus =
        mvc.get().uri("/admin/v1/change-requests?resourceType=ussd_menu&status=PENDING").with(CHECKER).exchange();
    assertThat(menus).hasStatus(200);
    assertThat(body(menus).path("items").valueStream().map(i -> i.path("resourceId").asString()).toList())
        .containsExactly("main");

    MvcTestResult templates = mvc.get().uri("/admin/v1/change-requests?resourceType=notify_template").with(CHECKER).exchange();
    assertThat(templates).hasStatus(200);
    assertThat(body(templates).path("items").valueStream().map(i -> i.path("resourceId").asString()).toList())
        .containsExactly("TXN_P2P_RECEIVED/SMS/en");

    assertThat(mvc.get().uri("/admin/v1/change-requests?resourceType=menu").with(CHECKER).exchange()).hasStatus(400);
  }

  @Test
  void anUnknownMnoAndAMalformedBodyAreRefused() throws IOException {
    MvcTestResult unknown = proposeMenu(MAKER, "main", menuBody(seedTree(), "[{\"mno\":\"airtel\"}]"));
    assertThat(unknown).hasStatus(400);
    assertThat(body(unknown).path("code").asString()).isEqualTo("MW-VAL-040");

    MvcTestResult noActivations = proposeMenu(MAKER, "main", menuBody(seedTree(), "[]"));
    assertThat(noActivations).hasStatus(400);
    assertThat(body(noActivations).path("code").asString()).isEqualTo("MW-VAL-001");
  }

  @Test
  void oneOpenRequestPerMenuAndTheMakerNeverDecidesOwnVersion() throws IOException {
    RequestPostProcessor both = operator("ops-both01", "ops-maker", "ops-checker");
    MvcTestResult first = proposeMenu(both, "main", menuBody(seedTree(), "[{\"mno\":\"robi\"}]"));
    assertThat(first).hasStatus(201);

    MvcTestResult second = proposeMenu(MAKER, "main", menuBody(seedTree(), "[{\"mno\":\"bl\"}]"));
    assertThat(second).hasStatus(409);
    assertThat(body(second).path("code").asString()).isEqualTo("MW-VAL-031");

    MvcTestResult own = approve(both, body(first).path("id").asString());
    assertThat(own).hasStatus(403);
    assertThat(body(own).path("code").asString()).isEqualTo("MW-AUTH-030");
    assertThat(outbox("mw.config.changed.v1")).isEmpty();

    assertThat(proposeMenu(CHECKER, "other", menuBody(seedTree(), "[{\"mno\":\"robi\"}]"))).hasStatus(403);
    assertThat(proposeMenu(operator("viewer01", "viewer"), "other", menuBody(seedTree(), "[{\"mno\":\"robi\"}]")))
        .hasStatus(403);
  }

  @Test
  void aRejectedOrExpiredVersionIsNeverPublished() throws IOException {
    String id = body(proposeMenu(MAKER, "main", menuBody(seedTree(), "[{\"mno\":\"robi\"}]"))).path("id").asString();
    assertThat(reject(CHECKER, id)).hasStatus(200);
    String id2 = body(proposeMenu(MAKER, "main", menuBody(seedTree(), "[{\"mno\":\"robi\"}]"))).path("id").asString();
    clock.advance(Duration.ofHours(72));
    expiry.expire();

    assertThat(outbox("mw.config.changed.v1")).isEmpty();
    assertThat(jdbc.queryForList("select status from content_version order by version", String.class))
        .containsExactly("REJECTED", "EXPIRED");
    assertThat(jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id2))
        .isEqualTo("EXPIRED");
    audits("admin.change_request.rejected").forEach(a -> assertEvent(AUDIT_SCHEMA, a));
    assertThat(audits("admin.change_request.expired")).singleElement().satisfies(a -> assertEvent(AUDIT_SCHEMA, a));
  }

  // --- notify templates -----------------------------------------------------------------------

  @Test
  void anApprovedTemplateVersionIsPublished() {
    MvcTestResult created =
        proposeTemplate(MAKER, "TXN_P2P_RECEIVED/SMS/en",
            "{\"body\":\"You got Tk {{amount}} from {{counterparty}}. TrxID {{txnId}}\",\"senderId\":\"upay\","
                + "\"reason\":\"shorter text\"}");
    assertThat(created).hasStatus(201);
    assertResponse("/admin/v1/notify-templates/{code}/{channel}/{locale}/change-requests", "post", "201", body(created));

    assertThat(approve(CHECKER, body(created).path("id").asString())).hasStatus(200);

    JsonNode event = outbox("mw.config.changed.v1").getFirst();
    assertEvent(CONFIG_SCHEMA, event);
    assertThat(outboxKeys("mw.config.changed.v1")).containsExactly("template/TXN_P2P_RECEIVED/SMS/en");
    assertThat(event.path("configType").asString()).isEqualTo("TEMPLATE");
    assertThat(event.path("value").path("body").asString()).isEqualTo("You got Tk {{amount}} from {{counterparty}}. TrxID {{txnId}}");
    assertThat(event.path("value").path("validFrom").asString()).isEqualTo(NOW.toString());
    assertThat(audits("admin.change_request.approved").getFirst().path("resource").asString())
        .isEqualTo("notify_template/TXN_P2P_RECEIVED/SMS/en");

    MvcTestResult versions = mvc.get().uri("/admin/v1/notify-templates/TXN_P2P_RECEIVED/SMS/en/versions").with(CHECKER).exchange();
    assertResponse("/admin/v1/notify-templates/{code}/{channel}/{locale}/versions", "get", "200", body(versions));
    assertThat(body(versions).path("items").get(0).path("version").asInt()).isEqualTo(1);
  }

  @Test
  void aTemplateWithASectionOrABadKeyIsRefusedAtMakerTime() {
    MvcTestResult section =
        proposeTemplate(MAKER, "TXN_P2P_RECEIVED/SMS/en", "{\"body\":\"{{#vip}}Hi{{/vip}}\",\"reason\":\"x\"}");
    assertThat(section).hasStatus(400);
    assertThat(body(section).path("code").asString()).isEqualTo("MW-VAL-040");

    MvcTestResult badLocale = proposeTemplate(MAKER, "TXN_P2P_RECEIVED/SMS/fr", "{\"body\":\"Hi\",\"reason\":\"x\"}");
    assertThat(badLocale).hasStatus(400);
    assertThat(body(badLocale).path("details").get(0).path("field").asString()).isEqualTo("locale");
    assertThat(jdbc.queryForObject("select count(*) from content_version", Integer.class)).isZero();
  }
}
