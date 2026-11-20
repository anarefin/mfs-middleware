package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import mfs.mw.admin.api.service.ChannelClientProjection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The channel client registry through maker-checker (ticket 100a): a maker files UPSERT, the maker
 * can never decide it, a different checker approves; the approval stores the row and audits it in
 * one transaction and, once committed, writes the APISIX consumer and consumer group through the
 * Admin API (a stand-in here, holding the objects like APISIX's etcd). An Admin API that is down
 * never fails the approval: the reconcile writes the consumer later.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "mw.apisix.enabled=true", "mw.apisix.admin-key=test-admin-key"})
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class ChannelClientChangeRequestIT {

  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  /** "consumers/<id>" -> the stored body, as APISIX keeps it. */
  static final Map<String, String> OBJECTS = new ConcurrentHashMap<>();
  static final List<String> CALLS = new CopyOnWriteArrayList<>();
  static volatile boolean down;
  static final HttpServer APISIX = startStandIn();

  static HttpServer startStandIn() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/apisix/admin/", ChannelClientChangeRequestIT::admin);
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void admin(HttpExchange ex) throws IOException {
    String key = ex.getRequestURI().getPath().substring("/apisix/admin/".length());
    String method = ex.getRequestMethod();
    if (down) {
      respond(ex, 503, "{\"error_msg\":\"etcd unavailable\"}");
      return;
    }
    if (!"test-admin-key".equals(ex.getRequestHeaders().getFirst("X-API-KEY"))) {
      respond(ex, 401, "{\"error_msg\":\"missing apikey\"}");
      return;
    }
    CALLS.add(method + " " + key);
    switch (method) {
      case "PUT" -> {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        OBJECTS.put(key, body);
        respond(ex, 200, "{\"key\":\"/apisix/" + key + "\",\"value\":" + body + "}");
      }
      case "DELETE" -> respond(ex, OBJECTS.remove(key) == null ? 404 : 200, "{}");
      default -> {
        if (key.equals("consumers") || key.equals("consumer_groups")) {
          StringBuilder list = new StringBuilder();
          OBJECTS.forEach((k, v) -> {
            if (k.startsWith(key + "/")) {
              list.append(list.isEmpty() ? "" : ",").append("{\"key\":\"/apisix/").append(k).append("\",\"value\":").append(v).append("}");
            }
          });
          respond(ex, 200, "{\"total\":0,\"list\":[" + list + "]}");
        } else if (OBJECTS.containsKey(key)) {
          // APISIX adds its own fields to what was stored
          String v = OBJECTS.get(key);
          respond(ex, 200, "{\"key\":\"/apisix/" + key + "\",\"value\":" + v.substring(0, v.length() - 1)
              + ",\"create_time\":1,\"update_time\":2}}");
        } else {
          respond(ex, 404, "{\"message\":\"Key not found\"}");
        }
      }
    }
  }

  static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @AfterAll
  static void stopStandIn() {
    APISIX.stop(0);
  }

  @DynamicPropertySource
  static void apisix(DynamicPropertyRegistry registry) {
    registry.add("mw.apisix.admin-url", () -> "http://127.0.0.1:" + APISIX.getAddress().getPort());
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt().jwt(j -> j.subject("sub-" + username)
        .issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username)
        .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = operator("int-maker01", "integration-maker", "integration-checker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");
  static final RequestPostProcessor VIEWER = operator("ops-viewer01", "viewer");

  static final String GATEWAY =
      """
      {"action":"UPSERT","reason":"onboard MNO gateway","client":{"type":"MNO","authMethod":"BASIC",
       "allowedRoutes":["ussd-http-gp"],"ipAllowList":["10.20.0.0/16"],"ratePerSec":40,"dailyQuota":200000,"enabled":true}}
      """;

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final ChannelClientProjection projection;

  @Autowired
  ChannelClientChangeRequestIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json, ChannelClientProjection projection) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.projection = projection;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
    jdbc.update("delete from change_request");
    jdbc.update("delete from channel_client");
    OBJECTS.clear();
    CALLS.clear();
    down = false;
  }

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private MvcTestResult ask(RequestPostProcessor who, String clientId, String body) {
    return mvc.post().uri("/admin/v1/channel-clients/{id}/change-requests", clientId).with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private String asked(String clientId, String body) {
    MvcTestResult r = ask(MAKER, clientId, body);
    assertThat(r).hasStatus(201);
    JsonNode cr = body(r);
    assertValid("/admin/v1/channel-clients/{clientId}/change-requests", "post", "201", cr);
    assertThat(cr.path("resourceType").asString()).isEqualTo("channel_client");
    return cr.path("id").asString();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private JsonNode stored(String key) {
    return json.readTree(OBJECTS.get(key));
  }

  private static void assertValid(String path, String method, String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, path, method, status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  @Test
  void anApprovedClientIsStoredAuditedAndWrittenToApisixAfterTheCommit() {
    String id = asked("ussd-gp", GATEWAY);
    assertThat(CALLS).as("nothing reaches APISIX before the approval").isEmpty();

    // the maker never decides, even holding the checker role
    MvcTestResult own = approve(MAKER, id);
    assertThat(own).hasStatus(403);
    assertThat(body(own).path("code").asString()).isEqualTo("MW-AUTH-030");

    MvcTestResult approved = approve(CHECKER, id);

    assertThat(approved).hasStatus(200);
    JsonNode cr = body(approved);
    assertValid("/admin/v1/change-requests/{id}/approve", "post", "200", cr);
    assertThat(cr.path("status").asString()).isEqualTo("APPROVED");
    assertThat(cr.path("payload").path("version").asLong()).isEqualTo(1);

    JsonNode consumer = stored("consumers/ussd_gp");
    assertThat(consumer.path("username").asString()).isEqualTo("ussd_gp");
    assertThat(consumer.path("group_id").asString()).isEqualTo("client_ussd_gp");
    assertThat(consumer.path("plugins").path("basic-auth").path("username").asString()).isEqualTo("ussd-gp");
    assertThat(consumer.path("plugins").path("basic-auth").path("password").asString())
        .isEqualTo("$secret://vault/edge/ussd-gp/password");
    assertThat(consumer.path("labels").path("managed-by").asString()).isEqualTo("mw-admin-api");
    JsonNode group = stored("consumer_groups/client_ussd_gp").path("plugins");
    assertThat(group.path("limit-req").path("rate").asLong()).isEqualTo(40);
    assertThat(group.path("limit-count").path("count").asLong()).isEqualTo(200000);
    assertThat(group.path("limit-count").path("time_window").asInt()).isEqualTo(86400);
    assertThat(group.path("ip-restriction").path("whitelist").toString()).isEqualTo("[\"10.20.0.0/16\"]");

    MvcTestResult view = mvc.get().uri("/admin/v1/channel-clients/{id}", "ussd-gp").with(VIEWER).exchange();
    assertThat(view).hasStatus(200);
    JsonNode c = body(view);
    assertValid("/admin/v1/channel-clients/{clientId}", "get", "200", c);
    assertThat(c.path("version").asLong()).isEqualTo(1);
    assertThat(c.path("consumer").asString()).isEqualTo("ussd_gp");
    assertThat(c.path("approvedBy").asString()).isEqualTo("ops-checker01");

    List<JsonNode> audits = jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.audit.event.v1' order by id", String.class)
        .stream().map(json::readTree).toList();
    assertThat(audits).extracting(a -> a.path("action").asString())
        .containsExactly("admin.change_request.created", "admin.change_request.approved");
    audits.forEach(a -> assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(a)).as("%s", a).isEmpty());
    assertThat(audits.get(1).path("resource").asString()).isEqualTo("channel_client/ussd-gp");

    // re-applying the committed registry is a no-op at APISIX
    CALLS.clear();
    assertThat(projection.reconcile()).hasValueSatisfying(r -> assertThat(r.written()).isZero());
    assertThat(CALLS).noneMatch(call -> call.startsWith("PUT") || call.startsWith("DELETE"));
  }

  @Test
  void aChangeOfAnExistingClientIsTheNextVersionAndADisableDropsTheCredential() {
    approve(CHECKER, asked("ussd-gp", GATEWAY));

    MvcTestResult same = ask(MAKER, "ussd-gp", GATEWAY);
    assertThat(same).hasStatus(409);
    assertThat(body(same).path("code").asString()).isEqualTo("MW-VAL-030");

    MvcTestResult typeChange = ask(MAKER, "ussd-gp", GATEWAY.replace("\"MNO\"", "\"PARTNER\""));
    assertThat(typeChange).hasStatus(400);

    String id = asked("ussd-gp", GATEWAY.replace("\"enabled\":true", "\"enabled\":false"));
    JsonNode pending = body(mvc.get().uri("/admin/v1/change-requests/{id}", id).with(CHECKER).exchange());
    assertThat(pending.path("payload").path("baseVersion").asLong()).isEqualTo(1);
    assertThat(pending.path("diff").path("after").path("enabled").asBoolean()).isFalse();

    assertThat(approve(CHECKER, id)).hasStatus(200);

    JsonNode consumer = stored("consumers/ussd_gp");
    assertThat(consumer.has("plugins")).isFalse();
    assertThat(consumer.has("group_id")).isFalse();
    assertThat(OBJECTS).doesNotContainKey("consumer_groups/client_ussd_gp");
    assertThat(body(mvc.get().uri("/admin/v1/channel-clients/{id}", "ussd-gp").with(VIEWER).exchange())
        .path("version").asLong()).isEqualTo(2);
  }

  @Test
  void anApisixOutageNeverFailsTheApprovalAndTheReconcileCatchesUp() {
    String id = asked("ussd-robi", GATEWAY.replace("ussd-http-gp", "ussd-http-robi"));
    down = true;

    assertThat(approve(CHECKER, id)).hasStatus(200);
    assertThat(OBJECTS).isEmpty();

    down = false;
    assertThat(projection.reconcile()).hasValueSatisfying(r -> assertThat(r.written()).isEqualTo(1));
    assertThat(OBJECTS).containsKeys("consumers/ussd_robi", "consumer_groups/client_ussd_robi");
  }

  @Test
  void aRejectedRequestNeverReachesApisixAndClientsWithoutConsumerAreOnlyStored() {
    String id = asked("ussd-bl", GATEWAY);
    MvcTestResult rejected = mvc.post().uri("/admin/v1/change-requests/{id}/reject", id).with(CHECKER)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wrong limits\"}").exchange();
    assertThat(rejected).hasStatus(200);
    assertThat(CALLS).isEmpty();

    String app = asked("customer-app", """
        {"action":"UPSERT","reason":"seed","client":{"type":"APP","authMethod":"JWT",
         "allowedRoutes":["app-v1","auth-v1"],"ipAllowList":[],"enabled":true}}
        """);
    assertThat(approve(CHECKER, app)).hasStatus(200);
    assertThat(OBJECTS).isEmpty();

    MvcTestResult list = mvc.get().uri("/admin/v1/channel-clients").with(MAKER).exchange();
    assertThat(list).hasStatus(200);
    JsonNode page = body(list);
    assertValid("/admin/v1/channel-clients", "get", "200", page);
    assertThat(page.path("items")).hasSize(1);
    assertThat(page.path("items").get(0).has("consumer")).isFalse();
  }

  @Test
  void rolesAndInputAreChecked() {
    assertThat(ask(VIEWER, "ussd-gp", GATEWAY)).hasStatus(403);
    assertThat(ask(operator("auditor01", "auditor"), "ussd-gp", GATEWAY)).hasStatus(403);
    assertThat(ask(operator("sec01", "security-admin"), "ussd-gp", GATEWAY)).hasStatus(403);
    assertThat(ask(MAKER, "Bad_Id", GATEWAY)).hasStatus(400);
    assertThat(ask(MAKER, "ussd-gp", GATEWAY.replace("10.20.0.0/16", "not an address"))).hasStatus(400);
    assertThat(ask(MAKER, "ussd-gp", GATEWAY.replace("\"enabled\":true", "\"enabled\":true,\"secret\":\"x\""))).hasStatus(400);
    assertThat(mvc.get().uri("/admin/v1/channel-clients/{id}", "nobody").with(VIEWER).exchange()).hasStatus(404);
    // integration staff see channel client requests, as partner requests
    String id = asked("ussd-gp", GATEWAY);
    assertThat(mvc.get().uri("/admin/v1/change-requests/{id}", id).with(operator("int-checker02", "integration-checker"))
        .exchange()).hasStatus(200);
    MvcTestResult secAdmin = approve(operator("sec02", "security-admin"), id);
    assertThat(secAdmin).hasStatus(403);
  }
}
