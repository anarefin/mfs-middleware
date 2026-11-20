package mfs.mw.compliance.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import mfs.mw.compliance.TestInfrastructure;
import mfs.mw.compliance.client.CryptoClient;
import mfs.mw.compliance.dto.CryptoEnvelope;
import mfs.mw.compliance.security.InternalTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * eKYC end to end on the service (ticket 124): the internal API, PostgreSQL, the outbox, the real {@code
 * EcNidClient} against a WireMock EC NID service, the real {@code CoreWalletClient} against a WireMock core (and its
 * token endpoint), and a reversible stand-in for mw-crypto. Covers submit, EC call, match, the KYC level set on the
 * core, then APPROVED and {@code mw.kyc.completed.v1} once in the outbox, the core down (retryable, no second EC
 * call), DOB-only match to manual review, mismatch, EC not found, timeout and open breaker (retryable),
 * PII at rest (no clear NID, DOB or name in any column) and the log (no clear NID or name in any line).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
@ExtendWith(OutputCaptureExtension.class)
class KycApplicationIT {

  static final WireMockServer EC = new WireMockServer(options().dynamicPort());
  static final WireMockServer CORE = new WireMockServer(options().dynamicPort());

  static {
    EC.start();
    CORE.start();
  }

  @DynamicPropertySource
  static void ec(DynamicPropertyRegistry r) {
    r.add("mw.compliance.ec.base-url", EC::baseUrl);
    r.add("mw.compliance.ec.timeout", () -> "600ms");
    r.add("mw.compliance.core.base-url", CORE::baseUrl);
    r.add("mw.compliance.crypto.token-uri", () -> CORE.baseUrl() + "/token");
  }

  @AfterAll
  static void stop() {
    EC.stop();
    CORE.stop();
  }

  static void coreUp() {
    CORE.resetAll();
    CORE.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"access_token\":\"core-token\",\"expires_in\":300}")));
    CORE.stubFor(put(urlPathMatching("/core/v1/accounts/[^/]+/kyc-level")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"walletId\":\"01712345678\",\"kycLevel\":\"FULL\",\"applicationId\":\"00000000-0000-4000-8000-000000000000\"}")));
  }

  static final String NID = "1990123456789";
  static final String NAME_EN = "Rahim Uddin";
  static final String NAME_BN = "রহিম উদ্দিন";
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, 5};

  @LocalServerPort int port;
  @MockitoBean CryptoClient crypto;

  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final CircuitBreakerRegistry breakers;

  @Autowired
  KycApplicationIT(JdbcTemplate jdbc, JsonMapper json, CircuitBreakerRegistry breakers) {
    this.jdbc = jdbc;
    this.json = json;
    this.breakers = breakers;
  }

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void fakeCrypto() {
    when(crypto.tokeniseNid(anyString())).thenAnswer(i -> "NID_" + Integer.toHexString(i.getArgument(0).hashCode()));
    when(crypto.encrypt(anyString(), any(), any())).thenAnswer(i -> new CryptoEnvelope("kek-column.v1", "d3JhcA==",
        "aXY=", Base64.getEncoder().encodeToString(flip(i.getArgument(1))), null));
    when(crypto.decrypt(any())).thenAnswer(i -> flip(Base64.getDecoder().decode(((CryptoEnvelope) i.getArgument(0)).ciphertext())));
    EC.resetAll();
    coreUp();
    breakers.circuitBreaker("ec-nid").reset();
  }

  @AfterEach
  void closeBreaker() {
    breakers.circuitBreaker("ec-nid").reset();
  }

  static byte[] flip(byte[] in) {
    byte[] out = new byte[in.length];
    for (int i = 0; i < in.length; i++) {
      out[i] = (byte) (in[in.length - 1 - i] ^ 0x5A);
    }
    return out;
  }

  void ecKnows(String nid, String dob, String en, String bn) {
    EC.stubFor(post(urlEqualTo("/ec/v1/nid/verify")).withRequestBody(matchingJsonPath("$.nid", equalTo(nid)))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
            "{\"nid\":\"" + nid + "\",\"dob\":\"" + dob + "\",\"nameEn\":\"" + en + "\",\"nameBn\":\"" + bn
                + "\",\"fatherName\":\"Karim Uddin\",\"motherName\":\"Amena Begum\",\"address\":\"Dhaka\"}")));
  }

  static final String GATEWAY = "mw-app-gateway";
  static final String AGENT = "DSO-17";

  /** As mw-app-gateway on behalf of the assisting DSO {@link #AGENT}. */
  HttpResponse<String> call(String method, String path, String body) throws Exception {
    return callAs(GATEWAY, "AGENT", AGENT, method, path, body);
  }

  HttpResponse<String> callAs(String client, String subjectType, String subjectId, String method, String path,
      String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", InternalTokens.bearer(client))
        .header("Content-Type", "application/json");
    if (subjectType != null) {
      b = b.header("X-Mw-Subject-Type", subjectType).header("X-Mw-Subject-Id", subjectId);
    }
    b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
        : b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  String apply(String nid, String nameEn) throws Exception {
    return apply("01712345678", nid, nameEn);
  }

  /** A DSO-assisted application of {@code wallet}, with the three images. */
  String apply(String wallet, String nid, String nameEn) throws Exception {
    HttpResponse<String> res = call("POST", "/internal/v1/kyc/applications", json.writeValueAsString(Map.of(
        "walletId", wallet, "channel", "DSO", "agentId", AGENT, "nid", nid, "dob", "1990-05-17",
        "nameEn", nameEn, "nameBn", NAME_BN, "consent", Map.of("purpose", "EKYC_ONBOARDING"))));
    assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
    String id = json.readTree(res.body()).path("applicationId").asString();
    for (String type : List.of("NID_FRONT", "NID_BACK", "SELFIE")) {
      HttpResponse<String> doc = call("POST", "/internal/v1/kyc/applications/" + id + "/documents",
          json.writeValueAsString(Map.of("type", type, "contentType", "image/jpeg",
              "content", Base64.getEncoder().encodeToString(JPEG))));
      assertThat(doc.statusCode()).as(doc.body()).isEqualTo(201);
    }
    return id;
  }

  int outboxRows(String id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox WHERE topic = 'mw.kyc.completed.v1' AND payload->>'applicationId' = ?",
        Integer.class, id);
  }

  @Test
  void matchingApplicationIsFullAndPublishedOnce(CapturedOutput output) throws Exception {
    ecKnows(NID, "1990-05-17", "RAHIM UDDIN", NAME_BN);
    String id = apply(NID, NAME_EN);

    HttpResponse<String> res = call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null);

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    JsonNode body = json.readTree(res.body());
    assertThat(body.path("status").asString()).isEqualTo("APPROVED");
    assertThat(body.path("kycLevel").asString()).isEqualTo("FULL");
    // The level is set on the core (decision a), with this service's token, before the event.
    CORE.verify(1, putRequestedFor(urlEqualTo("/core/v1/accounts/01712345678/kyc-level"))
        .withHeader("Authorization", equalTo("Bearer core-token"))
        .withRequestBody(matchingJsonPath("$.kycLevel", equalTo("FULL")))
        .withRequestBody(matchingJsonPath("$.applicationId", equalTo(id))));
    assertThat(body.path("nidMasked").asString()).endsWith("789").doesNotContain(NID);
    assertThat(outboxRows(id)).isEqualTo(1);
    Map<String, Object> event = jdbc.queryForMap(
        "SELECT payload::text AS p FROM outbox WHERE payload->>'applicationId' = ?", id);
    assertThat(event.get("p").toString()).contains("\"kycLevel\": \"FULL\"").doesNotContain(NID).doesNotContain("Rahim");
    // CMP-04: the EC call carried the consent id.
    String consentId = body.path("consentId").asString();
    EC.verify(1, postRequestedFor(urlEqualTo("/ec/v1/nid/verify"))
        .withRequestBody(matchingJsonPath("$.consentId", equalTo(consentId))));

    // A second submit is refused and publishes nothing more.
    assertThat(call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null).statusCode()).isEqualTo(409);
    assertThat(outboxRows(id)).isEqualTo(1);

    // Log: no clear NID or name in any line.
    assertThat(output.getAll()).doesNotContain(NID).doesNotContain(NAME_EN).doesNotContain(NAME_BN)
        .doesNotContain("RAHIM UDDIN").doesNotContain("Karim Uddin");
  }

  @Test
  void anotherDobIsMismatchedAndPublishesNothing() throws Exception {
    String nid = "1990123456111";
    ecKnows(nid, "1991-01-01", "RAHIM UDDIN", NAME_BN);
    String id = apply(nid, NAME_EN);

    JsonNode body = json.readTree(call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null).body());

    assertThat(body.path("status").asString()).isEqualTo("MISMATCHED");
    assertThat(body.path("reason").asString()).isEqualTo("DOB_MISMATCH");
    assertThat(outboxRows(id)).isZero();
  }

  @Test
  void unknownNidIsMismatched() throws Exception {
    EC.stubFor(post(urlEqualTo("/ec/v1/nid/verify")).willReturn(aResponse().withStatus(404)));
    String id = apply("1990123456000", NAME_EN);

    JsonNode body = json.readTree(call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null).body());

    assertThat(body.path("status").asString()).isEqualTo("MISMATCHED");
    assertThat(body.path("reason").asString()).isEqualTo("NID_NOT_FOUND");
  }

  @Test
  void ecTimeoutIsTmoAndTheApplicationCanBeSubmittedAgain() throws Exception {
    String nid = "1990123456999";
    EC.stubFor(post(urlEqualTo("/ec/v1/nid/verify")).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));
    String id = apply(nid, NAME_EN);

    HttpResponse<String> res = call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null);

    assertThat(res.statusCode()).isEqualTo(504);
    JsonNode err = json.readTree(res.body());
    assertThat(err.path("code").asString()).isEqualTo("MW-TMO-EC_NID");
    assertThat(err.path("retryable").asBoolean()).isTrue();
    assertThat(json.readTree(call("GET", "/internal/v1/kyc/applications/" + id, null).body()).path("status").asString())
        .isEqualTo("FAILED");
    // A timeout is not retried by the client (the EC may still be working on it).
    EC.verify(1, postRequestedFor(urlEqualTo("/ec/v1/nid/verify")));

    EC.resetAll();
    ecKnows(nid, "1990-05-17", "RAHIM UDDIN", NAME_BN);
    JsonNode again = json.readTree(call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null).body());
    assertThat(again.path("status").asString()).isEqualTo("APPROVED");
  }

  @Test
  void coreDownLeavesTheMatchRetryableAndAResubmitSetsTheLevelWithoutCallingTheEcAgain() throws Exception {
    String nid = "1990123456777";
    ecKnows(nid, "1990-05-17", "RAHIM UDDIN", NAME_BN);
    String id = apply(nid, NAME_EN);
    CORE.stubFor(put(urlPathMatching("/core/v1/accounts/[^/]+/kyc-level")).willReturn(aResponse().withStatus(503)));

    HttpResponse<String> res = call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null);

    assertThat(res.statusCode()).as(res.body()).isEqualTo(503);
    assertThat(json.readTree(res.body()).path("retryable").asBoolean()).isTrue();
    JsonNode held = json.readTree(call("GET", "/internal/v1/kyc/applications/" + id, null).body());
    assertThat(held.path("status").asString()).isEqualTo("MATCHED");
    assertThat(outboxRows(id)).as("no event before the core has the level").isZero();

    coreUp();
    JsonNode again = json.readTree(call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null).body());
    assertThat(again.path("status").asString()).isEqualTo("APPROVED");
    assertThat(outboxRows(id)).isEqualTo(1);
    EC.verify(1, postRequestedFor(urlEqualTo("/ec/v1/nid/verify")));
  }

  /** Product decision (124b): a DOB-only match is never auto-issued a level; an operator decides (ticket 134). */
  @Test
  void aDobOnlyMatchIsHeldForManualReviewWithoutALevelOrAnEvent() throws Exception {
    String nid = "1990123456888";
    ecKnows(nid, "1990-05-17", "KARIM HOSSAIN", "করিম হোসেন");
    String id = apply(nid, NAME_EN);

    JsonNode body = json.readTree(call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null).body());

    assertThat(body.path("status").asString()).isEqualTo("MANUAL_REVIEW");
    assertThat(body.path("reason").asString()).isEqualTo("NAME_MISMATCH");
    assertThat(body.has("kycLevel")).isFalse();
    assertThat(outboxRows(id)).isZero();
    CORE.verify(0, putRequestedFor(anyUrl()));
  }

  @Test
  void openBreakerIsPartnerCircuitOpenAndRetryable() throws Exception {
    String id = apply("1990123456222", NAME_EN);
    breakers.circuitBreaker("ec-nid").transitionToForcedOpenState();

    HttpResponse<String> res = call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null);

    assertThat(res.statusCode()).isEqualTo(503);
    JsonNode err = json.readTree(res.body());
    assertThat(err.path("code").asString()).isEqualTo("MW-SYS-PARTNER_CIRCUIT_OPEN");
    assertThat(err.path("retryable").asBoolean()).isTrue();
    assertThat(EC.getAllServeEvents()).isEmpty();
  }

  /** Security review: no IDOR. Another customer or agent gets 404; only mw-admin-api looks up unscoped. */
  @Test
  void onlyTheOwnerTheAssistingAgentOrTheConsoleSeesAnApplication() throws Exception {
    String id = apply("1990123456444", NAME_EN);
    String path = "/internal/v1/kyc/applications/" + id;

    assertThat(callAs(GATEWAY, "WALLET", "01712345678", "GET", path, null).statusCode()).isEqualTo(200);
    assertThat(callAs(GATEWAY, "AGENT", AGENT, "GET", path, null).statusCode()).isEqualTo(200);
    assertThat(callAs(GATEWAY, "WALLET", "01812345678", "GET", path, null).statusCode()).isEqualTo(404);
    assertThat(callAs(GATEWAY, "AGENT", "DSO-99", "GET", path, null).statusCode()).isEqualTo(404);
    assertThat(callAs(GATEWAY, "AGENT", "DSO-99", "POST", path + "/submit", null).statusCode()).isEqualTo(404);
    assertThat(callAs(GATEWAY, "AGENT", "DSO-99", "POST", path + "/documents", json.writeValueAsString(Map.of(
        "type", "SELFIE", "contentType", "image/jpeg", "content", Base64.getEncoder().encodeToString(JPEG)))).statusCode())
        .isEqualTo(404);
    // A channel that names no end user is refused; the console reads unscoped.
    assertThat(callAs(GATEWAY, null, null, "GET", path, null).statusCode()).isEqualTo(403);
    assertThat(callAs("mw-admin-api", null, null, "GET", path, null).statusCode()).isEqualTo(200);
    assertThat(EC.getAllServeEvents()).isEmpty();
  }

  /** Security review: the unscoped console client reads only; every write needs a WALLET or AGENT subject. */
  @Test
  void anUnscopedClientsWritesAreRefused() throws Exception {
    String id = apply("1990123456555", NAME_EN);
    String path = "/internal/v1/kyc/applications/" + id;
    String create = json.writeValueAsString(Map.of("walletId", "01712345670", "channel", "DSO", "agentId", "DSO-77",
        "nid", "1990123456556", "dob", "1990-05-17", "nameEn", NAME_EN, "consent", Map.of("purpose", "EKYC_ONBOARDING")));
    String doc = json.writeValueAsString(Map.of("type", "SELFIE", "contentType", "image/jpeg",
        "content", Base64.getEncoder().encodeToString(JPEG)));

    for (HttpResponse<String> res : List.of(
        callAs("mw-admin-api", null, null, "POST", "/internal/v1/kyc/applications", create),
        callAs("mw-admin-api", null, null, "POST", path + "/documents", doc),
        callAs("mw-admin-api", null, null, "POST", path + "/submit", null))) {
      assertThat(res.statusCode()).as(res.body()).isEqualTo(403);
      assertThat(json.readTree(res.body()).path("code").asString()).isEqualTo("MW-AUTH-002");
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_application WHERE agent_id = 'DSO-77'", Integer.class))
        .isZero();
    assertThat(EC.getAllServeEvents()).isEmpty();
  }

  /** Security review: an NID already verified for another wallet goes to manual review, no EC call, no level. */
  @Test
  void anNidAnotherWalletHoldsGoesToManualReview() throws Exception {
    String nid = "1990123456333";
    ecKnows(nid, "1990-05-17", "RAHIM UDDIN", NAME_BN);
    String first = apply("01712345678", nid, NAME_EN);
    assertThat(json.readTree(call("POST", "/internal/v1/kyc/applications/" + first + "/submit", null).body())
        .path("status").asString()).isEqualTo("APPROVED");

    String second = apply("01912345678", nid, NAME_EN);
    JsonNode body = json.readTree(call("POST", "/internal/v1/kyc/applications/" + second + "/submit", null).body());

    assertThat(body.path("status").asString()).isEqualTo("MANUAL_REVIEW");
    assertThat(body.path("reason").asString()).isEqualTo("NID_IN_USE");
    assertThat(body.has("kycLevel")).isFalse();
    assertThat(outboxRows(second)).isZero();
    EC.verify(1, postRequestedFor(urlEqualTo("/ec/v1/nid/verify")));
  }

  @Test
  void submitBeforeEveryImageIsRefused() throws Exception {
    String self = "01712345679";
    HttpResponse<String> res = callAs(GATEWAY, "WALLET", self, "POST", "/internal/v1/kyc/applications",
        json.writeValueAsString(Map.of("walletId", self, "channel", "APP", "nid", NID, "dob", "1990-05-17",
            "nameEn", NAME_EN, "consent", Map.of("purpose", "EKYC_ONBOARDING"))));
    String id = json.readTree(res.body()).path("applicationId").asString();

    HttpResponse<String> submit = callAs(GATEWAY, "WALLET", self, "POST", "/internal/v1/kyc/applications/" + id + "/submit", null);

    assertThat(submit.statusCode()).isEqualTo(409);
    assertThat(json.readTree(submit.body()).path("code").asString()).isEqualTo("MW-VAL-022");
    assertThat(EC.getAllServeEvents()).isEmpty();
  }

  @Test
  void imageOfAnotherTypeThanDeclaredIsRefused() throws Exception {
    ecKnows(NID, "1990-05-17", "RAHIM UDDIN", NAME_BN);
    String id = apply(NID, NAME_EN);
    HttpResponse<String> res = call("POST", "/internal/v1/kyc/applications/" + id + "/documents",
        json.writeValueAsString(Map.of("type", "SELFIE", "contentType", "image/png",
            "content", Base64.getEncoder().encodeToString(JPEG))));
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(json.readTree(res.body()).path("code").asString()).isEqualTo("MW-VAL-021");
  }

  @Test
  void noCallerTokenIs401() throws Exception {
    HttpResponse<String> res = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/internal/v1/kyc/applications/" + java.util.UUID.randomUUID()))
        .build(), HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(401);
  }

  /** FL-02, CMP-05: no column of the compliance tables holds the clear NID, date of birth or a name. */
  @Test
  void piiAtRestIsNeverClear() throws Exception {
    ecKnows(NID, "1990-05-17", "RAHIM UDDIN", NAME_BN);
    String id = apply(NID, NAME_EN);
    call("POST", "/internal/v1/kyc/applications/" + id + "/submit", null);

    List<String> cells = new ArrayList<>();
    for (String table : List.of("consent", "kyc_application", "kyc_document", "outbox")) {
      jdbc.query("SELECT row_to_json(t)::text FROM " + table + " t", rs -> {
        cells.add(rs.getString(1));
      });
    }
    assertThat(cells).isNotEmpty();
    String all = String.join("\n", cells);
    assertThat(all).doesNotContain(NID).doesNotContain("1990-05-17").doesNotContain("Rahim")
        .doesNotContain("RAHIM").doesNotContain(NAME_BN).doesNotContain("Karim");
    // The image is an envelope, not the bytes.
    String blob = jdbc.queryForObject(
        "SELECT blob_enc->>'ciphertext' FROM kyc_document WHERE application_id = ?::uuid AND type = 'SELFIE'",
        String.class, id);
    assertThat(blob).isNotEqualTo(Base64.getEncoder().encodeToString(JPEG));
  }
}
