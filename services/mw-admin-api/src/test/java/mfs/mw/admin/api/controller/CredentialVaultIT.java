package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import mfs.mw.admin.api.scheduler.PartnerSeedJob;
import mfs.mw.admin.api.service.TestCertificates;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Partner credential upload against a real Vault (ticket 131): the service runs with a token of
 * exactly the policy {@code deploy/provision/lib.sh} gives it (its own secret tree readable, its
 * staging area writable, the adapters' per-partner secrets create / update / patch only). An
 * upload is staged at once and the change request row never holds the secret; a different
 * integration checker's approval patches the field into {@code secret/mw-adapter-<family>/<partner>}
 * (the partner's other fields stay) and drops the staged copy; {@code GET /admin/v1/credentials}
 * returns metadata only; the service's token cannot read the secret back. A rejection drops the
 * staged copy and writes nothing.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "mw.credentials.vault.enabled=true"})
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class CredentialVaultIT {

  static final String ROOT = "test-root-token";
  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  static final String SECRET = "fake-ocs-api-key-0123456789abcdef";

  /** The policy deploy/provision/lib.sh (approle + admin_credential_policy) writes for mw-admin-api. */
  static final String POLICY =
      """
      path "secret/data/mw-admin-api" { capabilities = ["read"] }
      path "secret/data/mw-admin-api/*" { capabilities = ["read"] }
      path "secret/data/mw-admin-api/staged-credentials/*" { capabilities = ["create", "update", "read"] }
      path "secret/metadata/mw-admin-api/staged-credentials/*" { capabilities = ["delete"] }
      path "secret/data/mw-adapter-telco/*" { capabilities = ["create", "update", "patch"] }
      path "secret/data/mw-adapter-billpay/*" { capabilities = ["create", "update", "patch"] }
      path "secret/data/mw-adapter-bank/*" { capabilities = ["create", "update", "patch"] }
      """;

  static final GenericContainer<?> VAULT =
      new GenericContainer<>("hashicorp/vault:2.2.2")
          .withCommand("server", "-dev", "-dev-root-token-id=" + ROOT, "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  static final HttpClient HTTP = HttpClient.newHttpClient();
  static final JsonMapper JSON = JsonMapper.builder().build();
  static String serviceToken;

  @DynamicPropertySource
  static void vault(DynamicPropertyRegistry registry) {
    if (!VAULT.isRunning()) {
      VAULT.start();
      vault(ROOT, "PUT", "/v1/sys/policies/acl/mw-admin-api",
          JSON.writeValueAsString(Map.of("policy", POLICY)));
      serviceToken = vault(ROOT, "POST", "/v1/auth/token/create",
          "{\"policies\":[\"mw-admin-api\"],\"no_default_policy\":true,\"ttl\":\"1h\"}")
          .path("auth").path("client_token").asString();
    }
    registry.add("mw.credentials.vault.uri", CredentialVaultIT::vaultUri);
    registry.add("mw.credentials.vault.token", () -> serviceToken);
  }

  static String vaultUri() {
    return "http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200);
  }

  static JsonNode vault(String token, String method, String path, String body) {
    HttpResponse<String> r = vaultCall(token, method, path, body);
    assertThat(r.statusCode()).as("%s %s: %s", method, path, r.body()).isBetween(200, 299);
    return r.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(r.body());
  }

  static HttpResponse<String> vaultCall(String token, String method, String path, String body) {
    try {
      return HTTP.send(HttpRequest.newBuilder(URI.create(vaultUri() + path)).header("X-Vault-Token", token)
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
          .build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** A secret's fields as Vault's operator (root) reads them; empty when absent. */
  static JsonNode secret(String path) {
    HttpResponse<String> r = vaultCall(ROOT, "GET", "/v1/secret/data/" + path, null);
    return r.statusCode() == 404 ? JSON.createObjectNode() : JSON.readTree(r.body()).path("data").path("data");
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt().jwt(j -> j.subject("sub-" + username)
        .issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username)
        .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = operator("int-maker01", "integration-maker");
  static final RequestPostProcessor CHECKER = operator("int-checker01", "integration-checker");
  static final RequestPostProcessor OPS_CHECKER = operator("ops-checker01", "viewer", "ops-checker");
  static final RequestPostProcessor VIEWER = operator("ops-viewer01", "viewer");

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final PartnerSeedJob seeds;

  @Autowired
  CredentialVaultIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json, PartnerSeedJob seeds) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.seeds = seeds;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from change_request");
    jdbc.update("delete from credential_meta");
    jdbc.update("delete from partner.partner");
    seeds.seedAll();
    vaultCall(ROOT, "DELETE", "/v1/secret/metadata/mw-adapter-telco/ocs", null);
    vaultCall(ROOT, "DELETE", "/v1/secret/metadata/mw-adapter-bank/c2wbank", null);
    // the partner's secret already holds another field (ticket 91 provisioning): it must survive
    vault(ROOT, "POST", "/v1/secret/data/mw-adapter-telco/ocs", "{\"data\":{\"callback-hmac\":\"fake-existing-value\"}}");
  }

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private MvcTestResult upload(RequestPostProcessor who, String partnerId, String name, String body) {
    return mvc.post().uri("/admin/v1/partners/{p}/credentials/{n}/change-requests", partnerId, name).with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private JsonNode uploaded(String partnerId, String name, String body) {
    MvcTestResult r = upload(MAKER, partnerId, name, body);
    assertThat(r).hasStatus(201);
    JsonNode cr = body(r);
    assertValid("/admin/v1/partners/{partnerId}/credentials/{name}/change-requests", "post", "201", cr);
    assertThat(cr.path("resourceType").asString()).isEqualTo("partner_credential");
    return cr;
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private String rowText(String id) {
    return jdbc.queryForObject("select payload::text || diff::text || reason from change_request where id = ?::uuid",
        String.class, id);
  }

  private static void assertValid(String path, String method, String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, path, method, status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  static String apiKey(String secret) {
    return "{\"action\":\"UPLOAD\",\"reason\":\"rotate OCS key\",\"type\":\"API_KEY\",\"secret\":\"" + secret
        + "\",\"notAfter\":\"2030-01-01T00:00:00Z\"}";
  }

  @Test
  void anApprovedUploadLandsInThePartnersVaultSecretAndOnlyMetadataIsEverShown() {
    JsonNode cr = uploaded("ocs", "ocs-api-key", apiKey(SECRET));
    String id = cr.path("id").asString();
    assertThat(cr.toString()).doesNotContain(SECRET);
    assertThat(rowText(id)).doesNotContain(SECRET);
    String staged = cr.path("payload").path("stagedRef").asString();
    assertThat(secret("mw-admin-api/staged-credentials/" + staged).path("ocs-api-key").asString()).isEqualTo(SECRET);
    assertThat(secret("mw-adapter-telco/ocs").has("ocs-api-key")).isFalse();

    // the maker never decides it; nor does a checker outside integration
    assertThat(approve(MAKER, id)).hasStatus(403);
    assertThat(approve(OPS_CHECKER, id)).hasStatus(403);
    MvcTestResult approved = approve(CHECKER, id);
    assertThat(approved).hasStatus(200);
    assertThat(body(approved).path("status").asString()).isEqualTo("APPROVED");
    assertThat(body(approved).toString()).doesNotContain(SECRET);

    JsonNode fields = secret("mw-adapter-telco/ocs");
    assertThat(fields.path("ocs-api-key").asString()).isEqualTo(SECRET);
    assertThat(fields.path("callback-hmac").asString()).isEqualTo("fake-existing-value");
    assertThat(secret("mw-admin-api/staged-credentials/" + staged).isEmpty()).isTrue();

    MvcTestResult list = mvc.get().uri("/admin/v1/credentials?partnerId=ocs").with(VIEWER).exchange();
    assertThat(list).hasStatus(200);
    JsonNode page = body(list);
    assertValid("/admin/v1/credentials", "get", "200", page);
    assertThat(page.toString()).doesNotContain(SECRET);
    JsonNode item = page.path("items").get(0);
    assertThat(item.path("credentialId").asString()).isEqualTo("ocs/ocs-api-key");
    assertThat(item.path("adapter").asString()).isEqualTo("mw-adapter-telco");
    assertThat(item.path("kid").asString()).isEqualTo("ocs-api-key-v1");
    assertThat(item.path("uploadedBy").asString()).isEqualTo("int-maker01");
    assertThat(item.path("approvedBy").asString()).isEqualTo("int-checker01");
    assertThat(item.path("notAfter").asString()).isEqualTo("2030-01-01T00:00:00Z");

    // nothing in the outbox (audit included) carries the secret
    String outbox = String.join("\n", jdbc.queryForList("select payload::text from outbox", String.class));
    assertThat(outbox).contains("admin.change_request.approved").doesNotContain(SECRET);
    jdbc.queryForList("select payload::text from outbox where topic = 'mw.audit.event.v1'", String.class)
        .forEach(e -> assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(json.readTree(e))).isEmpty());

    // the service's own token can write the partner's secret but never read it back
    HttpResponse<String> readBack = vaultCall(serviceToken, "GET", "/v1/secret/data/mw-adapter-telco/ocs", null);
    assertThat(readBack.statusCode()).isEqualTo(403);
    assertThat(readBack.body()).doesNotContain(SECRET);

    // a rotation is the next version, the other field still kept
    String second = "fake-ocs-api-key-rotated-0123456789";
    JsonNode cr2 = uploaded("ocs", "ocs-api-key", apiKey(second));
    assertThat(cr2.path("diff").path("before").path("kid").asString()).isEqualTo("ocs-api-key-v1");
    assertThat(approve(CHECKER, cr2.path("id").asString())).hasStatus(200);
    assertThat(secret("mw-adapter-telco/ocs").path("ocs-api-key").asString()).isEqualTo(second);
    assertThat(secret("mw-adapter-telco/ocs").path("callback-hmac").asString()).isEqualTo("fake-existing-value");
    JsonNode again = body(mvc.get().uri("/admin/v1/credentials").with(VIEWER).exchange());
    assertThat(again.path("items").get(0).path("version").asLong()).isEqualTo(2);
  }

  @Test
  void aClientCertificateBecomesTheAdaptersKeyStoreFieldsAndShowsOnTheExpiryDashboard() {
    TestCertificates.Generated g = TestCertificates.generate("c2wbank-client", "EC", 20);
    String body = json.writeValueAsString(Map.of("action", "UPLOAD", "reason", "bank mTLS", "type", "CLIENT_CERT",
        "certificatePem", g.certificatePem(), "privateKeyPem", g.privateKeyPem()));
    JsonNode cr = uploaded("c2wbank", "c2w-mtls", body);
    String id = cr.path("id").asString();
    assertThat(rowText(id)).doesNotContain("PRIVATE KEY").doesNotContain(g.privateKeyPem().substring(40, 80));
    assertThat(cr.path("payload").path("meta").path("fingerprint").asString()).startsWith("sha256:");
    assertThat(approve(CHECKER, id)).hasStatus(200);

    JsonNode fields = secret("mw-adapter-bank/c2wbank");
    assertThat(fields.has("c2w-mtls-keystore")).isTrue();
    assertThat(fields.path("c2w-mtls-keystore-password").asString()).hasSizeGreaterThanOrEqualTo(32);

    MvcTestResult soon = mvc.get().uri("/admin/v1/credentials/expiring?days=30").with(VIEWER).exchange();
    assertThat(soon).hasStatus(200);
    JsonNode page = body(soon);
    assertValid("/admin/v1/credentials/expiring", "get", "200", page);
    assertThat(page.path("items")).hasSize(1);
    assertThat(page.path("items").get(0).path("subject").asString()).contains("CN=c2wbank-client");
    assertThat(page.path("items").get(0).path("daysLeft").asLong()).isBetween(18L, 20L);
    assertThat(page.toString()).doesNotContain(fields.path("c2w-mtls-keystore-password").asString());
    assertThat(body(mvc.get().uri("/admin/v1/credentials/expiring?days=10").with(VIEWER).exchange()).path("items"))
        .isEmpty();
    assertThat(mvc.get().uri("/admin/v1/credentials/expiring?days=0").with(VIEWER).exchange()).hasStatus(400);
  }

  @Test
  void aRejectedUploadDropsTheStagedCopyAndWritesNothing() {
    JsonNode cr = uploaded("ocs", "ocs-api-key", apiKey(SECRET));
    String staged = cr.path("payload").path("stagedRef").asString();
    MvcTestResult r = mvc.post().uri("/admin/v1/change-requests/{id}/reject", cr.path("id").asString()).with(CHECKER)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wrong key\"}").exchange();
    assertThat(r).hasStatus(200);
    assertThat(secret("mw-admin-api/staged-credentials/" + staged).isEmpty()).isTrue();
    assertThat(secret("mw-adapter-telco/ocs").has("ocs-api-key")).isFalse();
    assertThat(body(mvc.get().uri("/admin/v1/credentials").with(VIEWER).exchange()).path("items")).isEmpty();
  }

  @Test
  void onlyIntegrationMakersUploadAndProblemsNeverEchoTheUpload() throws Exception {
    assertThat(upload(operator("ops-maker01", "ops-maker"), "ocs", "k", apiKey(SECRET))).hasStatus(403);
    assertThat(upload(VIEWER, "ocs", "k", apiKey(SECRET))).hasStatus(403);
    assertThat(upload(MAKER, "nope", "k", apiKey(SECRET))).hasStatus(404);
    MvcTestResult bad = upload(MAKER, "ocs", "k", apiKey("too-short-key"));
    assertThat(bad).hasStatus(400);
    assertThat(body(bad).path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(bad.getResponse().getContentAsString()).doesNotContain("too-short-key");
    MvcTestResult extra = upload(MAKER, "ocs", "k",
        "{\"action\":\"UPLOAD\",\"reason\":\"x\",\"type\":\"API_KEY\",\"secret\":\"" + SECRET + "\",\"unknown\":1}");
    assertThat(extra).hasStatus(400);
    assertThat(extra.getResponse().getContentAsString()).doesNotContain(SECRET);
    assertThat(jdbc.queryForObject("select count(*) from change_request", Integer.class)).isZero();
  }
}
