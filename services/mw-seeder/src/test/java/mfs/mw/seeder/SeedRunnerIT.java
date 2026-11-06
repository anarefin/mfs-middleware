package mfs.mw.seeder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole seeder, as the container runs it ({@link SeederApplication#run}), against WireMock
 * stand-ins for Keycloak, mw-admin-api, mw-core-mock and the readiness probes, each stateful
 * (WireMock scenarios: a user exists once it was created, a partner is enabled once its change was
 * approved, ...). The first run seeds and exits 0; the second exits 0 with no create call at all
 * and the same manifest; a platform that never gets ready exits 1.
 */
class SeedRunnerIT {

  private static final Map<String, List<String>> PERSONAS =
      Map.of(
          "ops/seed-ops-maker", List.of("viewer", "ops-maker"),
          "ops/seed-ops-checker", List.of("viewer", "ops-checker"),
          "ops/seed-ops-viewer", List.of("viewer"),
          "ops/seed-ops-compliance", List.of("compliance-officer"),
          "portals/seed-org-maker", List.of("org-maker"),
          "portals/seed-org-checker", List.of("org-checker"),
          "customers/seed-customer01", List.of("customer"),
          "customers/seed-customer02", List.of("customer"),
          "customers/seed-agent01", List.of("agent"),
          "customers/seed-merchant01", List.of("merchant"));

  /** The API suite's run customers (tests/api/run_customer.mjs): seed-run-customer01..24, 01714NN0001. */
  private static final int RUN_CUSTOMERS = 24;

  private static final Map<String, List<String>> USERS = new java.util.LinkedHashMap<>(PERSONAS);
  private static final Map<String, String> WALLETS =
      new java.util.LinkedHashMap<>(
          Map.of(
              "01712000001", "CUSTOMER", "01712000002", "CUSTOMER", "01812000001", "AGENT", "01912000001", "MERCHANT",
              "01312000001", "ORG"));

  static {
    for (int i = 1; i <= RUN_CUSTOMERS; i++) {
      USERS.put(String.format("customers/seed-run-customer%02d", i), List.of("customer"));
      WALLETS.put(String.format("01714%02d0001", i), "CUSTOMER");
    }
  }

  @TempDir Path dir;

  private final WireMockServer wm = new WireMockServer(options().dynamicPort());
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void start() {
    wm.start();
    stubPlatform();
  }

  @AfterEach
  void stop() {
    wm.stop();
  }

  private String[] args(String... extra) {
    String base = "http://localhost:" + wm.port();
    List<String> a =
        new ArrayList<>(
            List.of(
                "--mw.seeder.endpoints.keycloak=" + base,
                "--mw.seeder.endpoints.admin-api=" + base,
                "--mw.seeder.endpoints.core-mock=" + base,
                "--mw.seeder.readiness[0].name=platform",
                "--mw.seeder.readiness[0].url=" + base + "/ready",
                "--mw.seeder.manifest-path=" + dir.resolve("seed/manifest.json"),
                "--mw.seeder.keycloak-admin.password=fake-admin-password",
                "--mw.seeder.realms.ops.password=fake-ops-password",
                "--mw.seeder.realms.ops.totp-secret=fake-totp-secret",
                "--mw.seeder.realms.portals.password=fake-portals-password",
                "--mw.seeder.realms.customers.password=fake-customers-password",
                "--mw.seeder.initial-backoff=10ms",
                "--mw.seeder.max-backoff=50ms"));
    a.addAll(List.of(extra));
    return a.toArray(String[]::new);
  }

  @Test
  void theFirstRunSeedsEverythingAndTheSecondChangesNothing() throws Exception {
    assertThat(SeederApplication.run(args())).isZero();

    assertThat(requests(RequestMethod.POST, "/admin/realms/[a-z]+/users")).hasSize(USERS.size());
    assertThat(requests(RequestMethod.PUT, "/core-mock/v1/wallets/.*")).hasSize(WALLETS.size());
    assertThat(requests(RequestMethod.POST, "/admin/v1/change-requests/.*/approve")).hasSize(4);
    LoggedRequest maker =
        requests(RequestMethod.POST, "/admin/realms/ops/users").stream()
            .filter(r -> r.getBodyAsString().contains("seed-ops-maker")).findFirst().orElseThrow();
    JsonNode rep = json.readTree(maker.getBodyAsString());
    assertThat(rep.path("credentials").findValuesAsString("type")).containsExactly("password", "otp");
    assertThat(rep.path("credentials").get(1).path("secretData").asString()).isEqualTo("{\"value\":\"fake-totp-secret\"}");
    LoggedRequest compliance =
        requests(RequestMethod.POST, "/admin/realms/ops/users").stream()
            .filter(r -> r.getBodyAsString().contains("seed-ops-compliance")).findFirst().orElseThrow();
    assertThat(json.readTree(compliance.getBodyAsString()).path("credentials").findValuesAsString("type"))
        .as("the compliance officer logs in with password + TOTP like the other ops personas")
        .containsExactly("password", "otp");
    LoggedRequest opsToken =
        requests(RequestMethod.POST, "/realms/ops/protocol/openid-connect/token").getFirst();
    assertThat(opsToken.getBodyAsString()).contains("client_id=mw-e2e-ops", "totp=");
    Path manifest = dir.resolve("seed/manifest.json");
    String first = Files.readString(manifest);
    JsonNode m = json.readTree(first);
    assertThat(m.path("personas")).hasSize(USERS.size());
    assertThat(m.path("wallets")).hasSize(WALLETS.size());
    assertThat(m.path("partners").get(0).path("enabled").asBoolean()).isTrue();
    assertThat(m.path("ussdMenus").get(0).path("version").asInt()).isEqualTo(1);
    assertThat(m.path("templates")).hasSize(2);
    assertThat(first).doesNotContain("fake-ops-password", "fake-totp-secret", "fake-admin-password");

    wm.resetRequests();
    assertThat(SeederApplication.run(args())).isZero();

    List<LoggedRequest> writes =
        wm.findAll(anyRequestedFor(anyUrl())).stream()
            .filter(r -> !r.getMethod().equals(RequestMethod.GET))
            .filter(r -> !r.getUrl().contains("/protocol/openid-connect/token"))
            .toList();
    assertThat(writes).as("create/change calls on the second run").isEmpty();
    assertThat(Files.readString(manifest)).isEqualTo(first);
  }

  @Test
  void aPlatformThatNeverGetsReadyEndsTheRunWithExitCode1() {
    wm.stubFor(get("/ready").atPriority(1).willReturn(aResponse().withStatus(503)));

    assertThat(SeederApplication.run(args("--mw.seeder.step-timeouts.await-platform-ready=300ms"))).isEqualTo(1);
    assertThat(requests(RequestMethod.POST, "/admin/realms/[a-z]+/users")).isEmpty();
  }

  private List<LoggedRequest> requests(RequestMethod method, String pathRegex) {
    return wm.findAll(anyRequestedFor(anyUrl())).stream()
        .filter(r -> r.getMethod().equals(method) && r.getUrl().split("\\?")[0].matches(pathRegex))
        .toList();
  }

  // --- the stand-ins ----------------------------------------------------------------------------

  private void stubPlatform() {
    wm.stubFor(get("/ready").willReturn(aResponse().withStatus(200)));
    wm.stubFor(post("/realms/master/protocol/openid-connect/token").willReturn(okJson("{\"access_token\":\"admin\",\"expires_in\":60}")));
    wm.stubFor(post("/realms/ops/protocol/openid-connect/token").willReturn(okJson("{\"access_token\":\"ops\",\"expires_in\":300}")));
    for (String realm : List.of("ops", "portals", "customers")) {
      wm.stubFor(get(urlPathEqualTo("/admin/realms/" + realm + "/clients")).willReturn(okJson("[{\"id\":\"c\"}]")));
    }
    USERS.forEach(this::stubUser);
    WALLETS.forEach(this::stubWallet);
    stubPartner();
    wm.stubFor(get(urlPathEqualTo("/admin/v1/change-requests")).willReturn(okJson("{\"items\":[],\"limit\":200,\"offset\":0}")));
    stubContent("menu-main", "/admin/v1/ussd-menus/main", "ussd-menu/main");
    stubContent("tpl-en", "/admin/v1/notify-templates/TXN_P2P_RECEIVED/SMS/en", "template/TXN_P2P_RECEIVED/SMS/en");
    stubContent("tpl-bn", "/admin/v1/notify-templates/TXN_P2P_RECEIVED/SMS/bn", "template/TXN_P2P_RECEIVED/SMS/bn");
  }

  private void stubUser(String key, List<String> roles) {
    String realm = key.substring(0, key.indexOf('/'));
    String username = key.substring(key.indexOf('/') + 1);
    String id = "id-" + username;
    String users = "/admin/realms/" + realm + "/users";
    wm.stubFor(get(urlPathEqualTo(users)).withQueryParam("username", equalTo(username))
        .inScenario(key).whenScenarioStateIs(STARTED).willReturn(okJson("[]")));
    wm.stubFor(get(urlPathEqualTo(users)).withQueryParam("username", equalTo(username))
        .inScenario(key).whenScenarioStateIs("created")
        .willReturn(okJson("[{\"id\":\"" + id + "\",\"username\":\"" + username + "\"}]")));
    wm.stubFor(post(users).withRequestBody(matchingJsonPath("$.username", equalTo(username)))
        .inScenario(key).whenScenarioStateIs(STARTED).willSetStateTo("created")
        .willReturn(aResponse().withStatus(201).withHeader("Location", "http://kc" + users + "/" + id)));
    StringBuilder mapped = new StringBuilder("[");
    for (String role : roles) {
      wm.stubFor(get("/admin/realms/" + realm + "/roles/" + role).willReturn(okJson("{\"id\":\"r-" + role + "\",\"name\":\"" + role + "\"}")));
      mapped.append(mapped.length() > 1 ? "," : "").append("{\"name\":\"").append(role).append("\"}");
    }
    wm.stubFor(get(users + "/" + id + "/role-mappings/realm").willReturn(okJson(mapped + "]")));
    wm.stubFor(post(users + "/" + id + "/role-mappings/realm").willReturn(aResponse().withStatus(204)));
  }

  private void stubWallet(String walletId, String type) {
    String path = "/core-mock/v1/wallets/" + walletId;
    String view = "{\"walletId\":\"" + walletId + "\",\"type\":\"" + type + "\",\"status\":\"ACTIVE\",\"balanceMinor\":1,\"heldMinor\":0,\"availableMinor\":1}";
    wm.stubFor(get(path).inScenario(walletId).whenScenarioStateIs(STARTED)
        .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
            .withBody("{\"code\":\"MW-VAL-003\",\"message\":\"wallet not found\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}")));
    wm.stubFor(get(path).inScenario(walletId).whenScenarioStateIs("created").willReturn(okJson(view)));
    wm.stubFor(put(path).withRequestBody(matchingJsonPath("$.type", equalTo(type)))
        .inScenario(walletId).whenScenarioStateIs(STARTED).willSetStateTo("created")
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(view)));
  }

  /** ocs starts disabled at 20 s: one ENABLE request, approved, puts it back. */
  private void stubPartner() {
    String partner = "{\"partnerId\":\"ocs\",\"family\":\"telco\",\"version\":%d,\"config\":{\"enabled\":%s,\"timeoutMs\":20000}}";
    wm.stubFor(get("/admin/v1/partners/ocs").inScenario("ocs").whenScenarioStateIs(STARTED).willReturn(okJson(partner.formatted(0, "false"))));
    wm.stubFor(get("/admin/v1/partners/ocs").inScenario("ocs").whenScenarioStateIs("enabled").willReturn(okJson(partner.formatted(1, "true"))));
    wm.stubFor(post("/admin/v1/partners/ocs/change-requests").withRequestBody(matchingJsonPath("$.action", equalTo("ENABLE")))
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(changeRequest("cr-ocs", "PENDING"))));
    wm.stubFor(post("/admin/v1/change-requests/cr-ocs/approve").inScenario("ocs").willSetStateTo("enabled")
        .willReturn(okJson(changeRequest("cr-ocs", "APPROVED"))));
  }

  private void stubContent(String id, String base, String configKey) {
    String cr = "cr-" + id;
    wm.stubFor(get(base + "/versions?limit=200").inScenario(id).whenScenarioStateIs(STARTED)
        .willReturn(okJson("{\"items\":[],\"limit\":200,\"offset\":0}")));
    wm.stubFor(get(base + "/versions?limit=200").inScenario(id).whenScenarioStateIs("published")
        .willReturn(okJson("{\"items\":[{\"configKey\":\"" + configKey + "\",\"version\":1,\"status\":\"PUBLISHED\","
            + "\"changeRequestId\":\"" + cr + "\",\"makerId\":\"seed-ops-maker\",\"createdAt\":\"2026-11-06T00:00:00Z\"}],\"limit\":200,\"offset\":0}")));
    wm.stubFor(post(base + "/change-requests")
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(changeRequest(cr, "PENDING"))));
    wm.stubFor(post("/admin/v1/change-requests/" + cr + "/approve").inScenario(id).willSetStateTo("published")
        .willReturn(okJson(changeRequest(cr, "APPROVED"))));
  }

  private static String changeRequest(String id, String status) {
    return "{\"id\":\"" + id + "\",\"resourceType\":\"x\",\"resourceId\":\"x\",\"action\":\"X\",\"status\":\"" + status
        + "\",\"makerId\":\"seed-ops-maker\",\"payload\":{}}";
  }
}
