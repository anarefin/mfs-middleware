package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keys and the key ceremony (ticket 75) against a stand-in mw-crypto: maker and checker must be two
 * different security-admins with a fresh MFA (auth_time at most 5 min old); an approval calls the
 * mw-crypto key API with the change request id; a ceremony takes two custodians' components (passed
 * to mw-crypto, never stored or audited here), cannot be approved before both, is committed on
 * approval and aborted on rejection.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, KeyCeremonyApiIT.TestClock.class})
class KeyCeremonyApiIT {

  static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String TOKEN = "service-token-75";
  static final String C1 = "11".repeat(32);
  static final String C2 = "22".repeat(32);
  static final List<String> CALLS = new CopyOnWriteArrayList<>();
  static final Map<String, Integer> SHARES = new ConcurrentHashMap<>();
  static final Map<String, java.util.Set<String>> CUSTODIANS = new ConcurrentHashMap<>();
  static final HttpServer CRYPTO = startStub();

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    Clock testClock() {
      return new MutableClock(NOW);
    }
  }

  static final String KEYS = "{\"items\":[{\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"algorithm\":\"HMAC_SHA256\","
      + "\"provider\":\"pkcs11\",\"versions\":["
      + "{\"version\":1,\"kid\":\"pin-hmac.v1\",\"state\":\"VERIFY_ONLY\",\"createdAt\":\"2026-11-01T00:00:00Z\",\"rotatedAt\":\"2026-11-02T00:00:00Z\"},"
      + "{\"version\":2,\"kid\":\"pin-hmac.v2\",\"state\":\"ACTIVE\",\"createdAt\":\"2026-11-02T00:00:00Z\",\"rotatedAt\":null}]}]}";
  static final String KEY_V3 = "{\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"algorithm\":\"HMAC_SHA256\","
      + "\"provider\":\"pkcs11\",\"versions\":[{\"version\":3,\"kid\":\"pin-hmac.v3\",\"state\":\"ACTIVE\","
      + "\"createdAt\":\"2026-11-15T10:00:00Z\",\"rotatedAt\":null}]}";

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/token", ex -> respond(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}"));
      s.createContext("/internal/v1/", KeyCeremonyApiIT::crypto);
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void crypto(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = ex.getRequestURI().getPath();
    CALLS.add(ex.getRequestMethod() + " " + path + " " + body);
    if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      respond(ex, 401, "{\"code\":\"MW-AUTH-001\"}");
    } else if (path.equals("/internal/v1/keys")) {
      respond(ex, 200, KEYS);
    } else if (path.startsWith("/internal/v1/key-changes/") && "GET".equals(ex.getRequestMethod())) {
      respond(ex, 200, "{\"changeRequestId\":\"" + path.split("/")[4] + "\",\"applied\":false}");
    } else if (path.matches("/internal/v1/ceremonies/[0-9a-f-]+") && "PUT".equals(ex.getRequestMethod())) {
      // opened at filing time (ticket 75 review)
      respond(ex, 200, "{\"ceremonyId\":\"" + path.split("/")[4] + "\",\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\","
          + "\"version\":3,\"kid\":\"pin-hmac.v3\",\"state\":\"OPEN\",\"shares\":[],\"checkValue\":null}");
    } else if (path.startsWith("/internal/v1/key-changes/") || path.endsWith("/commit")) {
      respond(ex, 200, KEY_V3);
    } else if (path.endsWith("/abort")) {
      ex.sendResponseHeaders(204, -1);
      ex.close();
    } else if (path.endsWith("/components")) {
      String id = path.split("/")[4];
      String custodian = body.replaceAll(".*\"custodian\":\"([^\"]+)\".*", "$1");
      if (!CUSTODIANS.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(custodian)) {
        respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"The two components must come from two custodians\"}");
        return;
      }
      int n = SHARES.merge(id, 1, Integer::sum);
      String shares = n == 1
          ? "[{\"share\":1,\"custodian\":\"" + custodian + "\",\"enteredAt\":\"2026-11-15T10:00:00Z\"}]"
          : "[{\"share\":1,\"custodian\":\"seed-ops-secadmin1\",\"enteredAt\":\"2026-11-15T10:00:00Z\"},"
              + "{\"share\":2,\"custodian\":\"" + custodian + "\",\"enteredAt\":\"2026-11-15T10:01:00Z\"}]";
      respond(ex, 200, "{\"ceremonyId\":\"" + id + "\",\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"version\":3,"
          + "\"kid\":\"pin-hmac.v3\",\"state\":\"" + (n == 1 ? "OPEN" : "COMBINED") + "\",\"shares\":" + shares
          + ",\"checkValue\":" + (n == 1 ? "null" : "\"A1B2C3\"") + "}");
    } else {
      respond(ex, 404, "{\"code\":\"MW-VAL-003\"}");
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
  static void stopStub() {
    CRYPTO.stop(0);
  }

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + CRYPTO.getAddress().getPort();
    registry.add("mw.admin-api.crypto.base-url", () -> base);
    registry.add("mw.admin-api.internal-auth.token-uri", () -> base + "/token");
  }

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  KeyCeremonyApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
    jdbc.update("delete from change_request");
    CALLS.clear();
    SHARES.clear();
    CUSTODIANS.clear();
  }

  static RequestPostProcessor operator(String username, long mfaAgeSeconds, String... roles) {
    return jwt()
        .jwt(
            j ->
                j.subject("sub-" + username)
                    .issuer("http://keycloak:8080/realms/ops")
                    .claim("preferred_username", username)
                    .claim("auth_time", NOW.getEpochSecond() - mfaAgeSeconds)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor SEC1 = operator("seed-ops-secadmin1", 60, "security-admin");
  static final RequestPostProcessor SEC2 = operator("seed-ops-secadmin2", 60, "security-admin");
  static final RequestPostProcessor SEC2_STALE = operator("seed-ops-secadmin2", 600, "security-admin");
  static final RequestPostProcessor OPS_CHECKER = operator("ops-checker01", 60, "viewer", "ops-checker");

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private MvcTestResult post(RequestPostProcessor who, String uri, String body) {
    return mvc.post().uri(uri).with(who).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private static void assertValid(String path, String method, String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, path, method, status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  private String code(MvcTestResult r) {
    return body(r).path("code").asString();
  }

  private String rotateRequest() {
    MvcTestResult r = post(SEC1, "/admin/v1/keys/change-requests",
        "{\"action\":\"KEY_ROTATE\",\"keyId\":\"pin-hmac\",\"reason\":\"yearly rotation\"}");
    assertThat(r).hasStatus(201);
    assertValid("/admin/v1/keys/change-requests", "post", "201", body(r));
    return body(r).path("id").asString();
  }

  @Test
  void keysAreListedForSecurityAdminsAndAuditorsOnly() {
    MvcTestResult ok = mvc.get().uri("/admin/v1/keys").with(operator("ops-auditor01", 3600, "auditor")).exchange();
    assertThat(ok).hasStatus(200);
    assertValid("/admin/v1/keys", "get", "200", body(ok));
    assertThat(mvc.get().uri("/admin/v1/keys").with(OPS_CHECKER).exchange()).hasStatus(403);
  }

  @Test
  void anApprovedRotationCallsMwCryptoOnceWithBothNames() {
    String id = rotateRequest();
    JsonNode cr = body(mvc.get().uri("/admin/v1/change-requests/{id}", id).with(SEC2).exchange());
    assertThat(cr.path("resourceType").asString()).isEqualTo("key");
    assertThat(cr.path("diff").path("after").path("key").asString()).isEqualTo("pin-hmac.v3 ACTIVE");

    MvcTestResult approved = approve(SEC2, id);
    assertThat(approved).hasStatus(200);
    assertThat(body(approved).path("status").asString()).isEqualTo("APPROVED");
    assertThat(body(approved).path("payload").path("appliedKid").asString()).isEqualTo("pin-hmac.v3");
    assertThat(CALLS).filteredOn(c -> c.startsWith("PUT /internal/v1/key-changes/" + id)).singleElement()
        .satisfies(c -> assertThat(c).contains("\"action\":\"ROTATE\"", "\"makerId\":\"seed-ops-secadmin1\"",
            "\"checkerId\":\"seed-ops-secadmin2\""));
    // audited with the checker as actor
    List<JsonNode> audits = jdbc.queryForList("select payload::text from outbox where topic = 'mw.audit.event.v1'",
        String.class).stream().map(json::readTree).toList();
    assertThat(audits).extracting(a -> a.path("action").asString())
        .contains("admin.change_request.created", "admin.change_request.approved");
    audits.forEach(a -> assertThat(ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json")
        .validate(a)).as("%s", a).isEmpty());
  }

  @Test
  void theMakerCannotApproveOwnKeyRequest() {
    String id = rotateRequest();
    MvcTestResult own = approve(SEC1, id);
    assertThat(own).hasStatus(403);
    assertThat(code(own)).isEqualTo("MW-AUTH-030");
    assertThat(CALLS).noneMatch(c -> c.startsWith("PUT"));
  }

  @Test
  void aStaleMfaIsRefusedToTheMakerAndToTheChecker() {
    MvcTestResult maker = post(operator("seed-ops-secadmin1", 301, "security-admin"), "/admin/v1/keys/change-requests",
        "{\"action\":\"KEY_ROTATE\",\"keyId\":\"pin-hmac\",\"reason\":\"r\"}");
    assertThat(maker).hasStatus(403);
    assertThat(code(maker)).isEqualTo("MW-AUTH-031");

    String id = rotateRequest();
    MvcTestResult checker = approve(SEC2_STALE, id);
    assertThat(checker).hasStatus(403);
    assertThat(code(checker)).isEqualTo("MW-AUTH-031");
    // a token without auth_time is stale too
    RequestPostProcessor noAuthTime = jwt().jwt(j -> j.subject("sub-x").issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", "x").claim("realm_access", Map.of("roles", List.of("security-admin"))));
    assertThat(code(approve(noAuthTime, id))).isEqualTo("MW-AUTH-031");
    assertThat(CALLS).noneMatch(c -> c.startsWith("PUT"));
  }

  @Test
  void onlySecurityAdminsDecideKeyRequests() {
    String id = rotateRequest();
    MvcTestResult r = approve(OPS_CHECKER, id);
    assertThat(r).hasStatus(403);
    assertThat(code(r)).isEqualTo("MW-AUTH-002");
  }

  @Test
  void keyChangesThatDoNotFitTheKeyAreRefused() {
    assertThat(code(post(SEC1, "/admin/v1/keys/change-requests",
        "{\"action\":\"KEY_RETIRE\",\"keyId\":\"pin-hmac\",\"version\":2,\"reason\":\"r\"}"))).isEqualTo("MW-VAL-030");
    assertThat(code(post(SEC1, "/admin/v1/keys/change-requests",
        "{\"action\":\"KEY_GENERATE\",\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"reason\":\"r\"}"))).isEqualTo("MW-VAL-030");
    assertThat(post(SEC1, "/admin/v1/keys/change-requests",
        "{\"action\":\"KEY_RETIRE\",\"keyId\":\"pin-hmac\",\"version\":1,\"reason\":\"r\"}")).hasStatus(201);
  }

  @Test
  void aCeremonyTakesTwoCustodiansThenTheApprovalCommitsIt() {
    MvcTestResult started = post(SEC1, "/admin/v1/key-ceremonies",
        "{\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"reason\":\"migrate the PIN key\"}");
    assertThat(started).hasStatus(201);
    String id = body(started).path("id").asString();
    assertThat(body(started).path("action").asString()).isEqualTo("KEY_CEREMONY");

    MvcTestResult first = post(SEC1, "/admin/v1/key-ceremonies/" + id + "/components",
        "{\"component\":\"" + C1 + "\",\"checkValue\":\"AAAAAA\"}");
    assertThat(first).hasStatus(200);
    assertValid("/admin/v1/key-ceremonies/{id}/components", "post", "200", body(first));
    assertThat(body(first).path("shares")).hasSize(1);
    assertThat(body(first).has("checkValue")).isFalse();

    MvcTestResult early = approve(SEC2, id);
    assertThat(early).hasStatus(409);
    assertThat(code(early)).isEqualTo("MW-VAL-050");

    MvcTestResult twice = post(SEC1, "/admin/v1/key-ceremonies/" + id + "/components",
        "{\"component\":\"" + C2 + "\",\"checkValue\":\"BBBBBB\"}");
    assertThat(twice).hasStatus(409);
    assertThat(code(twice)).isEqualTo("MW-VAL-018");

    MvcTestResult second = post(SEC2, "/admin/v1/key-ceremonies/" + id + "/components",
        "{\"component\":\"" + C2 + "\",\"checkValue\":\"BBBBBB\"}");
    assertThat(second).hasStatus(200);
    assertThat(body(second).path("checkValue").asString()).isEqualTo("A1B2C3");
    assertValid("/admin/v1/key-ceremonies/{id}", "get", "200",
        body(mvc.get().uri("/admin/v1/key-ceremonies/{id}", id).with(SEC2).exchange()));

    // the components went to mw-crypto only: not in the request row, the outbox or the audit
    String stored = jdbc.queryForObject("select payload::text || diff::text from change_request where id = ?::uuid",
        String.class, id);
    String out = String.join("", jdbc.queryForList("select payload::text from outbox", String.class));
    assertThat(stored + out).doesNotContain(C1).doesNotContain(C2);
    assertThat(CALLS).anyMatch(c -> c.contains(C1));
    assertThat(out).contains("admin.key_ceremony.component_entered");

    MvcTestResult approved = approve(SEC2, id);
    assertThat(approved).hasStatus(200);
    assertThat(CALLS).anyMatch(c -> c.startsWith("POST /internal/v1/ceremonies/" + id + "/commit")
        && c.contains("\"makerId\":\"seed-ops-secadmin1\"") && c.contains("\"checkerId\":\"seed-ops-secadmin2\""));
  }

  @Test
  void aRejectedCeremonyIsAbortedAtMwCrypto() {
    String id = body(post(SEC1, "/admin/v1/key-ceremonies",
        "{\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"reason\":\"r\"}")).path("id").asString();
    assertThat(post(SEC2, "/admin/v1/change-requests/" + id + "/reject", "{\"reason\":\"wrong key\"}")).hasStatus(200);
    assertThat(CALLS).anyMatch(c -> c.startsWith("POST /internal/v1/ceremonies/" + id + "/abort"));
    assertThat(post(SEC2, "/admin/v1/key-ceremonies/" + id + "/components",
        "{\"component\":\"" + C1 + "\",\"checkValue\":\"AAAAAA\"}")).hasStatus(409);
  }

  @Test
  void aCeremonyMakesHmacKeysOnly() {
    MvcTestResult r = post(SEC1, "/admin/v1/key-ceremonies", "{\"keyId\":\"kek-file\",\"purpose\":\"KEK_FILE\",\"reason\":\"r\"}");
    assertThat(r).hasStatus(400);
    assertThat(code(r)).isEqualTo("MW-VAL-001");
  }

  @Test
  void aSecurityAdminSeesKeyRequestsOnly() {
    String id = rotateRequest();
    JsonNode page = body(mvc.get().uri("/admin/v1/change-requests").with(SEC2).exchange());
    assertThat(page.path("items")).extracting(i -> i.path("id").asString()).containsExactly(id);
    assertThat(mvc.get().uri("/admin/v1/change-requests?resourceType=transaction").with(SEC2).exchange()).hasStatus(403);
  }
}
