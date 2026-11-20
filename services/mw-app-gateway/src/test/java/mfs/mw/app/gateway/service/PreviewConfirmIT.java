package mfs.mw.app.gateway.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.app.gateway.RedisInfrastructure;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.TestTopics;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.dto.TxnAuthorisation;
import mfs.mw.app.gateway.messaging.OrchestratorStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Preview and confirm end to end in the gateway (ticket 105, FRS CH-APP-05, PLT-026) over real Kafka
 * and Redis: the quote from a WireMock mw-orchestrator (its real client and service token), the
 * preview kept in Redis, the confirm published to the orchestrator stub. Preview -> confirm completes
 * a P2P whose debited fee is the previewed one; a preview alone publishes nothing and asks mw-auth
 * nothing; a changed amount is refused.
 */
@SpringBootTest(
    properties = {
      "mw.app-gateway.reply-budget=3s",
      "mw.app-gateway.instance-id=it-gw-preview",
      "mw.app-gateway.consumers.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, RedisInfrastructure.class})
@DirtiesContext
class PreviewConfirmIT {

  static final String PAYER = "01711000001";
  static final String PAYEE = "01711000002";
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJpdCJ9.c2lnbmF0dXJl";
  /** The core's charges for the stub's completing amount: the stub's COMPLETED carries fee 500. */
  static final String QUOTE = "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":" + OrchestratorStub.COMPLETES
      + ",\"currency\":\"BDT\"},\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"},\"vat\":{\"valueMinor\":65,\"currency\":\"BDT\"},"
      + "\"commission\":{\"valueMinor\":0,\"currency\":\"BDT\"},\"total\":{\"valueMinor\":" + (OrchestratorStub.COMPLETES + 500)
      + ",\"currency\":\"BDT\"},\"limits\":{\"allowed\":true,\"remaining\":{}},"
      + "\"counterparty\":{\"type\":\"WALLET\",\"id\":\"0171****002\",\"name\":\"R.\",\"walletType\":\"CUSTOMER\"}}";

  static final WireMockServer HTTP = new WireMockServer(options().dynamicPort());

  static {
    HTTP.start();
  }

  private static OrchestratorStub orchestrator;

  private final MockMvcTester mvc;
  private final JsonMapper json;

  @MockitoBean AuthClient auth;

  @Autowired
  PreviewConfirmIT(MockMvcTester mvc, JsonMapper json) {
    this.mvc = mvc;
    this.json = json;
  }

  @DynamicPropertySource
  static void orchestratorAndKeycloak(DynamicPropertyRegistry r) {
    r.add("mw.app-gateway.orchestrator.base-url", () -> "http://127.0.0.1:" + HTTP.port());
    r.add("mw.app-gateway.auth.token-uri", () -> "http://127.0.0.1:" + HTTP.port() + "/token");
  }

  @BeforeAll
  static void topicsAndOrchestrator() {
    TestTopics.ensure("mw.txn.request.v1", "mw.txn.status.v1");
    orchestrator = new OrchestratorStub(TestTopics.bootstrap());
    HTTP.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"tok-it\",\"expires_in\":300}")));
    HTTP.stubFor(post(urlEqualTo("/internal/v1/quotes"))
        .withRequestBody(equalToJson("{\"walletId\":\"" + PAYER + "\",\"txnType\":\"P2P\"}", true, true))
        .willReturn(okJson(QUOTE)));
  }

  @AfterAll
  static void stop() {
    orchestrator.close();
    HTTP.stop();
  }

  @BeforeEach
  void ready() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, Instant.now().plusSeconds(60), false));
    TestTopics.awaitGroupStable("mw-app-gateway.reply.it-gw-preview", Duration.ofSeconds(60));
  }

  private MvcTestResult preview(long amount) {
    return mvc.post().uri("/app/v1/send-money/preview")
        .with(jwt().jwt(j -> j.claim("msisdn", PAYER).claim("role", "customer").claim("amr", List.of("pin", "device"))))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"creditParty\":{\"id\":\"%s\"},\"amount\":{\"valueMinor\":%d,\"currency\":\"BDT\"}}".formatted(PAYEE, amount))
        .exchange();
  }

  private MvcTestResult confirm(String previewId, String key, long amount) {
    return mvc.post().uri("/app/v1/send-money/confirm")
        .with(jwt().jwt(j -> j.claim("msisdn", PAYER).claim("role", "customer").claim("amr", List.of("pin", "device"))))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"previewId":"%s","creditParty":{"id":"%s"},"amount":{"valueMinor":%d,"currency":"BDT"},
             "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVydGV4dA=="}}
            """.formatted(previewId, PAYEE, amount))
        .exchange();
  }

  private static String body(MvcTestResult r) throws Exception {
    return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private long published(String key) {
    return orchestrator.requests().stream()
        .filter(r -> json.readTree(r.value()).path("idempotencyKey").asString().equals(key))
        .count();
  }

  @Test
  void previewThenConfirmCompletesWithThePreviewedFee() throws Exception {
    MvcTestResult p = preview(OrchestratorStub.COMPLETES);
    assertThat(p).hasStatus(200);
    long previewedFee = ((Number) JsonPath.read(body(p), "$.fee.valueMinor")).longValue();
    String key = "confirm-" + UUID.randomUUID();

    MvcTestResult c = confirm(JsonPath.read(body(p), "$.previewId"), key, OrchestratorStub.COMPLETES);

    assertThat(c).hasStatus(200);
    assertThat(c).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");
    assertThat(((Number) JsonPath.read(body(c), "$.fee.valueMinor")).longValue()).isEqualTo(previewedFee).isEqualTo(500);
    assertThat(published(key)).isEqualTo(1);
    var sent = json.readTree(orchestrator.requests().stream()
        .filter(r -> json.readTree(r.value()).path("idempotencyKey").asString().equals(key)).findFirst().orElseThrow().value());
    assertThat(sent.path("txnType").asString()).isEqualTo("P2P");
    assertThat(sent.path("amount").path("valueMinor").asLong()).isEqualTo(OrchestratorStub.COMPLETES);
    assertThat(sent.has("encryptedPin")).as("the PIN goes to mw-auth only").isFalse();
  }

  @Test
  void aPreviewAloneMovesNothing() {
    int before = orchestrator.requests().size();

    assertThat(preview(OrchestratorStub.COMPLETES)).hasStatus(200);

    assertThat(orchestrator.requests()).hasSize(before);
    verify(auth, never()).authorise(any());
  }

  @Test
  void aConfirmWithAnotherAmountIsRefusedAndNothingIsPublished() throws Exception {
    MvcTestResult p = preview(OrchestratorStub.COMPLETES);
    String key = "confirm-" + UUID.randomUUID();

    MvcTestResult c = confirm(JsonPath.read(body(p), "$.previewId"), key, OrchestratorStub.COMPLETES + 1);

    assertThat(c).hasStatus(400);
    assertThat(c).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-026");
    assertThat(published(key)).isZero();
  }
}
