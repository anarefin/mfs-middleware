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
 * stand-ins for Keycloak, mw-admin-api, mw-core-mock, mw-auth and the readiness probes, each stateful
 * (WireMock scenarios: a user exists once it was created, a partner is enabled once its change was
 * approved, ...). The first run seeds and exits 0; the second exits 0 with no create call at all
 * and the same manifest (mw-auth's idempotent seed-PIN call aside); a platform that never gets ready exits 1.
 */
class SeedRunnerIT {

  private static final Map<String, List<String>> PERSONAS =
      Map.of(
          "ops/seed-ops-maker", List.of("viewer", "ops-maker"),
          "ops/seed-ops-checker", List.of("viewer", "ops-checker"),
          "ops/seed-ops-viewer", List.of("viewer"),
          "ops/seed-ops-compliance", List.of("compliance-officer"),
          "ops/seed-ops-secadmin1", List.of("security-admin"),
          "ops/seed-ops-secadmin2", List.of("security-admin"),
          "portals/seed-org-maker", List.of("org-maker"),
          "portals/seed-org-checker", List.of("org-checker"));

  /** The realm imports' users (deploy/keycloak/realms): read for the manifest's importedUsers, never written. */
  private static final List<String> IMPORTED =
      List.of(
          "ops/ops-viewer01", "ops/ops-maker01", "ops/ops-checker01", "ops/ops-admin01", "ops/ops-compliance01",
          "ops/ops-auditor01", "ops/ops-e2e-maker01", "ops/ops-e2e-checker01", "portals/org-maker01",
          "portals/org-checker01", "portals/org-makerchecker01", "portals/org-checker02", "portals/sp-viewer01");

  /** The app users: listed in the manifest for an mw-auth login, never created in Keycloak (ticket 73). */
  private static final List<String> APP_USERS =
      new ArrayList<>(List.of("seed-customer01", "seed-customer02", "seed-agent01", "seed-merchant01",
          "seed-distributor01", "seed-dso01", "seed-partner01"));

  /** The API suite's run customers (tests/api/run_customer.mjs): seed-run-customer01..24, 01714NN0001. */
  private static final int RUN_CUSTOMERS = 24;

  private static final Map<String, List<String>> USERS = new java.util.LinkedHashMap<>(PERSONAS);
  private static final Map<String, String> WALLETS =
      new java.util.LinkedHashMap<>(
          Map.of(
              "01712000001", "CUSTOMER", "01712000002", "CUSTOMER", "01812000001", "AGENT", "01912000001", "MERCHANT",
              "01312000001", "ORG", "01612000001", "DISTRIBUTOR", "01512000001", "DSO", "01412000001", "ORG",
              "01312000002", "ORG"));

  static {
    for (int i = 1; i <= RUN_CUSTOMERS; i++) {
      APP_USERS.add(String.format("seed-run-customer%02d", i));
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
                "--mw.seeder.endpoints.auth=" + base,
                "--mw.seeder.app-pins.pin=13579",
                "--mw.seeder.readiness[0].name=platform",
                "--mw.seeder.readiness[0].url=" + base + "/ready",
                "--mw.seeder.manifest-path=" + dir.resolve("seed/manifest.json"),
                "--mw.seeder.keycloak-admin.password=fake-admin-password",
                "--mw.seeder.realms.ops.password=fake-ops-password",
                "--mw.seeder.realms.ops.totp-secret=fake-totp-secret",
                "--mw.seeder.realms.portals.password=fake-portals-password",
                "--mw.seeder.realms.portals.totp-secret=fake-portals-totp-secret",
                "--mw.seeder.initial-backoff=10ms",
                "--mw.seeder.max-backoff=50ms",
                // one channel client (ticket 100a) instead of the whole baseline list
                "--mw.seeder.channel-clients[0].client-id=ussd-gp",
                "--mw.seeder.channel-clients[0].type=MNO",
                "--mw.seeder.channel-clients[0].auth-method=BASIC",
                "--mw.seeder.channel-clients[0].allowed-routes[0]=ussd-http-gp",
                "--mw.seeder.channel-clients[0].rate-per-sec=100"));
    a.addAll(List.of(extra));
    return a.toArray(String[]::new);
  }

  @Test
  void theFirstRunSeedsEverythingAndTheSecondChangesNothing() throws Exception {
    assertThat(SeederApplication.run(args())).isZero();

    assertThat(requests(RequestMethod.POST, "/admin/realms/[a-z]+/users")).hasSize(USERS.size());
    assertThat(requests(RequestMethod.PUT, "/core-mock/v1/wallets/.*")).hasSize(WALLETS.size());
    assertThat(requests(RequestMethod.POST, "/admin/v1/change-requests/.*/approve")).hasSize(5);
    assertThat(requests(RequestMethod.POST, "/admin/v1/channel-clients/ussd-gp/change-requests").getFirst().getBodyAsString())
        .contains("\"authMethod\":\"BASIC\"", "\"ratePerSec\":100");
    LoggedRequest maker =
        requests(RequestMethod.POST, "/admin/realms/ops/users").stream()
            .filter(r -> r.getBodyAsString().contains("seed-ops-maker")).findFirst().orElseThrow();
    JsonNode rep = json.readTree(maker.getBodyAsString());
    assertThat(rep.path("credentials").findValuesAsString("type")).containsExactly("password", "otp");
    assertThat(rep.path("credentials").get(1).path("secretData").asString()).isEqualTo("{\"value\":\"fake-totp-secret\"}");
    assertThat(rep.path("firstName").asString()).isEqualTo("Nusrat");
    assertThat(rep.path("lastName").asString()).isEqualTo("Jahan");
    assertThat(requests(RequestMethod.PUT, "/core-mock/v1/wallets/01712000001").getFirst().getBodyAsString())
        .contains("\"holderName\":\"Sumaiya Islam\"");
    LoggedRequest compliance =
        requests(RequestMethod.POST, "/admin/realms/ops/users").stream()
            .filter(r -> r.getBodyAsString().contains("seed-ops-compliance")).findFirst().orElseThrow();
    assertThat(json.readTree(compliance.getBodyAsString()).path("credentials").findValuesAsString("type"))
        .as("the compliance officer logs in with password + TOTP like the other ops personas")
        .containsExactly("password", "otp");
    LoggedRequest orgMaker =
        requests(RequestMethod.POST, "/admin/realms/portals/users").stream()
            .filter(r -> r.getBodyAsString().contains("seed-org-maker")).findFirst().orElseThrow();
    JsonNode org = json.readTree(orgMaker.getBodyAsString());
    assertThat(org.path("credentials").findValuesAsString("type"))
        .as("the portals realm demands TOTP (ticket 73): the org personas get the pre-set secret")
        .containsExactly("password", "otp");
    assertThat(org.path("credentials").get(1).path("secretData").asString()).isEqualTo("{\"value\":\"fake-portals-totp-secret\"}");
    assertThat(requests(RequestMethod.GET, "/admin/realms/customers/.*")).as("no customers realm").isEmpty();
    LoggedRequest opsToken =
        requests(RequestMethod.POST, "/realms/ops/protocol/openid-connect/token").getFirst();
    assertThat(opsToken.getBodyAsString()).contains("client_id=mw-e2e-ops", "totp=");
    Path manifest = dir.resolve("seed/manifest.json");
    String first = Files.readString(manifest);
    JsonNode m = json.readTree(first);
    assertThat(m.path("personas")).hasSize(USERS.size() + APP_USERS.size());
    for (JsonNode p : m.path("personas")) {
      if (APP_USERS.contains(p.path("username").asString())) {
        assertThat(p.path("realm").asString()).isEqualTo("mw-auth");
        assertThat(p.path("passwordEnv").asString()).isEqualTo("MW_SEED_APP_PIN");
      }
    }
    assertThat(m.path("wallets")).hasSize(WALLETS.size());
    for (JsonNode w : m.path("wallets")) {
      assertThat(w.path("holderName").asString("")).as("every seeded wallet has a holder name").isNotBlank();
    }
    for (JsonNode p : m.path("personas")) {
      assertThat(p.path("displayName").asString("")).as("every persona has a display name").isNotBlank();
    }
    assertThat(m.path("importedUsers")).hasSize(IMPORTED.size());
    JsonNode e2eMaker = imported(m, "ops-e2e-maker01");
    assertThat(e2eMaker.path("displayName").asString()).isEqualTo("Name Ops-e2e-maker01");
    assertThat(e2eMaker.path("realm").asString()).isEqualTo("ops");
    assertThat(e2eMaker.path("roles").toString()).isEqualTo("[\"ops-maker\",\"viewer\"]");
    assertThat(e2eMaker.path("passwordEnv").asString()).isEqualTo("KC_OPS_TEST_PASSWORD");
    assertThat(e2eMaker.path("totpSecretEnv").asString()).isEqualTo("KC_OPS_TEST_TOTP_SECRET");
    assertThat(imported(m, "ops-maker01").path("totpSecretEnv").asString())
        .as("ops-*01 have the pre-set secret too: no authenticator app")
        .isEqualTo("KC_OPS_TEST_TOTP_SECRET");
    JsonNode orgMaker01 = imported(m, "org-maker01");
    assertThat(orgMaker01.path("attributes").path("orgId").asString()).isEqualTo("ORG001");
    assertThat(orgMaker01.path("loginClient").asString()).isEqualTo("mw-portal");
    assertThat(orgMaker01.path("totpSecretEnv").asString()).isEqualTo("KC_PORTALS_TEST_TOTP_SECRET");
    assertThat(requests(RequestMethod.POST, "/admin/realms/[a-z]+/users").stream().map(LoggedRequest::getBodyAsString))
        .noneMatch(b -> b.contains("ops-e2e-maker01") || b.contains("org-maker01"));
    assertThat(m.path("partners").get(0).path("enabled").asBoolean()).isTrue();
    assertThat(m.path("ussdMenus").get(0).path("version").asInt()).isEqualTo(1);
    assertThat(m.path("templates")).hasSize(2);
    assertThat(m.path("appPins").path("pinEnv").asString()).isEqualTo("MW_SEED_APP_PIN");
    // An organisation wallet gets no app PIN, except the partner app's disbursement wallet: it logs in at mw-auth.
    long appWallets = WALLETS.entrySet().stream()
        .filter(e -> !e.getValue().equals("ORG") || e.getKey().equals("01412000001")).count();
    assertThat(m.path("appPins").path("walletIds")).hasSize((int) appWallets + 5); // + the realm imports' app users
    assertThat(m.path("appPins").path("walletIds").toString())
        .contains("01412000001").doesNotContain("01312000001", "01312000002");
    assertThat(first).doesNotContain("fake-ops-password", "fake-totp-secret", "fake-portals-totp-secret", "fake-admin-password", "13579");
    LoggedRequest pins = requests(RequestMethod.POST, "/internal/v1/seed/pins").getFirst();
    assertThat(pins.getBodyAsString()).contains("\"keyId\":\"pin-transport.v1\"").doesNotContain("13579");
    JsonNode pinBody = json.readTree(pins.getBodyAsString());
    List<String> marked = new ArrayList<>();
    for (JsonNode pin : pinBody.path("pins")) {
      if (pin.hasNonNull("appRole")) {
        marked.add(pin.path("walletId").asString() + "=" + pin.path("appRole").asString());
      }
    }
    assertThat(marked).containsExactly("01412000001=PARTNER");

    wm.resetRequests();
    assertThat(SeederApplication.run(args())).isZero();

    List<LoggedRequest> writes =
        wm.findAll(anyRequestedFor(anyUrl())).stream()
            .filter(r -> !r.getMethod().equals(RequestMethod.GET))
            .filter(r -> !r.getUrl().contains("/protocol/openid-connect/token"))
            .filter(r -> !r.getUrl().equals("/internal/v1/seed/pins")) // idempotent: mw-auth answers UNCHANGED
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

  private static JsonNode imported(JsonNode manifest, String username) {
    for (JsonNode p : manifest.path("importedUsers")) {
      if (p.path("username").asString().equals(username)) {
        return p;
      }
    }
    throw new AssertionError("no imported user " + username);
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
    for (String realm : List.of("ops", "portals")) {
      wm.stubFor(get(urlPathEqualTo("/admin/realms/" + realm + "/clients")).willReturn(okJson("[{\"id\":\"c\"}]")));
    }
    USERS.forEach(this::stubUser);
    IMPORTED.forEach(this::stubImportedUser);
    WALLETS.forEach(this::stubWallet);
    stubPartner();
    stubChannelClient();
    stubAuth();
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

  /** A realm-import user: there from the start, with a profile, an orgId in portals and its realm roles. */
  private void stubImportedUser(String key) {
    String realm = key.substring(0, key.indexOf('/'));
    String username = key.substring(key.indexOf('/') + 1);
    String id = "imp-" + username;
    String users = "/admin/realms/" + realm + "/users";
    String attributes = realm.equals("portals") ? ",\"attributes\":{\"orgId\":[\"ORG001\"]}" : "";
    wm.stubFor(get(urlPathEqualTo(users)).withQueryParam("username", equalTo(username))
        .willReturn(okJson("[{\"id\":\"" + id + "\",\"username\":\"" + username + "\",\"firstName\":\"Name\",\"lastName\":\""
            + Character.toUpperCase(username.charAt(0)) + username.substring(1) + "\"" + attributes + "}]")));
    wm.stubFor(get(users + "/" + id + "/role-mappings/realm")
        .willReturn(okJson("[{\"name\":\"viewer\"},{\"name\":\"default-roles-" + realm + "\"},{\"name\":\"ops-maker\"}]")));
  }

  private void stubWallet(String walletId, String type) {
    String path = "/core-mock/v1/wallets/" + walletId;
    // Core-mock stores ORG as ORGANISATION (ticket 94) and returns that type.
    String stored = "ORG".equals(type) ? "ORGANISATION" : type;
    String view = "{\"walletId\":\"" + walletId + "\",\"type\":\"" + stored + "\",\"status\":\"ACTIVE\",\"balanceMinor\":1,\"heldMinor\":0,\"availableMinor\":1,"
        + "\"holderName\":\"Holder\"}";
    wm.stubFor(get(path).inScenario(walletId).whenScenarioStateIs(STARTED)
        .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
            .withBody("{\"code\":\"MW-VAL-003\",\"message\":\"wallet not found\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}")));
    wm.stubFor(get(path).inScenario(walletId).whenScenarioStateIs("created").willReturn(okJson(view)));
    wm.stubFor(put(path).withRequestBody(matchingJsonPath("$.type", equalTo(type)))
        .inScenario(walletId).whenScenarioStateIs(STARTED).willSetStateTo("created")
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(view)));
  }

  /** mw-auth: the PIN transport key (a real RSA key, so the seeder can encrypt) and the seed API. */
  private void stubAuth() {
    try {
      var g = java.security.KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      String b64 = java.util.Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
      String pem = "-----BEGIN PUBLIC KEY-----\\n" + b64 + "\\n-----END PUBLIC KEY-----\\n";
      wm.stubFor(get("/auth/v1/pin-key").willReturn(okJson(
          "{\"keyId\":\"pin-transport.v1\",\"algorithm\":\"RSA-OAEP-SHA-256\",\"publicKeyPem\":\"" + pem + "\"}")));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    wm.stubFor(post("/internal/v1/seed/pins").inScenario("pins").whenScenarioStateIs(STARTED).willSetStateTo("set")
        .willReturn(okJson("{\"results\":[{\"walletId\":\"01712000001\",\"outcome\":\"CREATED\"}]}")));
    wm.stubFor(post("/internal/v1/seed/pins").inScenario("pins").whenScenarioStateIs("set")
        .willReturn(okJson("{\"results\":[{\"walletId\":\"01712000001\",\"outcome\":\"UNCHANGED\"}]}")));
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

  /** ussd-gp is not registered yet: one UPSERT, approved, registers it (ticket 100a). */
  private void stubChannelClient() {
    String view = "{\"clientId\":\"ussd-gp\",\"type\":\"MNO\",\"authMethod\":\"BASIC\",\"allowedRoutes\":[\"ussd-http-gp\"],"
        + "\"ipAllowList\":[],\"ratePerSec\":100,\"enabled\":true,\"version\":1,\"consumer\":\"ussd_gp\","
        + "\"updatedAt\":\"2026-11-20T00:00:00Z\",\"updatedBy\":\"seed-ops-maker\",\"approvedBy\":\"seed-ops-checker\"}";
    wm.stubFor(get("/admin/v1/channel-clients/ussd-gp").inScenario("cc").whenScenarioStateIs(STARTED)
        .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
            .withBody("{\"code\":\"MW-VAL-003\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}")));
    wm.stubFor(get("/admin/v1/channel-clients/ussd-gp").inScenario("cc").whenScenarioStateIs("registered").willReturn(okJson(view)));
    wm.stubFor(post("/admin/v1/channel-clients/ussd-gp/change-requests").withRequestBody(matchingJsonPath("$.action", equalTo("UPSERT")))
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(changeRequest("cr-cc", "PENDING"))));
    wm.stubFor(post("/admin/v1/change-requests/cr-cc/approve").inScenario("cc").willSetStateTo("registered")
        .willReturn(okJson(changeRequest("cr-cc", "APPROVED"))));
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
