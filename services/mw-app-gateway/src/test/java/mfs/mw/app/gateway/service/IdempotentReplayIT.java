package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.TestTopics;
import mfs.mw.app.gateway.ValkeyInfrastructure;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stored original response (FRS PLT-011, ticket 99) over real Kafka and Valkey: once a key was
 * answered 200, a repeat with the same content gets that body again byte for byte (without asking
 * mw-auth or the orchestrator, whose answer could have changed since), and other content is 409.
 */
@SpringBootTest(
    properties = {
      "mw.app-gateway.reply-budget=3s",
      "mw.app-gateway.instance-id=it-gw-replay",
      "mw.app-gateway.consumers.enabled=true",
      "mw.app-gateway.idempotent-replay.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ValkeyInfrastructure.class})
@DirtiesContext
class IdempotentReplayIT {

  static final String PAYER = "01711000001";
  static final String PAYEE = "01711000002";
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJpdCJ9.c2lnbmF0dXJl";

  private static OrchestratorStub orchestrator;

  private final MockMvcTester mvc;
  private final JsonMapper json;

  @MockitoBean AuthClient auth;

  @Autowired
  IdempotentReplayIT(MockMvcTester mvc, JsonMapper json) {
    this.mvc = mvc;
    this.json = json;
  }

  @BeforeAll
  static void topicsAndOrchestrator() {
    TestTopics.ensure("mw.txn.request.v1", "mw.txn.status.v1");
    orchestrator = new OrchestratorStub(TestTopics.bootstrap());
  }

  @AfterAll
  static void stop() {
    orchestrator.close();
  }

  @BeforeEach
  void ready() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, Instant.now().plusSeconds(60), false));
    TestTopics.awaitGroupStable("mw-app-gateway.reply.it-gw-replay", Duration.ofSeconds(60));
  }

  private MvcTestResult submit(String key, long amount, String ciphertext) {
    return mvc.post()
        .uri("/app/v1/transactions")
        .with(jwt().jwt(j -> j.claim("msisdn", PAYER).claim("role", "customer").claim("amr", List.of("pin", "device"))))
        .header("Idempotency-Key", key)
        .header("X-Correlation-Id", "corr-replay")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"txnType":"P2P","creditParty":{"id":"%s"},"amount":{"valueMinor":%d,"currency":"BDT"},
             "encryptedPin":{"keyId":"core-pin-1","ciphertext":"%s"},"locale":"bn"}
            """
                .formatted(PAYEE, amount, ciphertext))
        .exchange();
  }

  private static String bytes(MvcTestResult r) throws Exception {
    return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private long published(String key) {
    return orchestrator.requests().stream()
        .filter(r -> json.readTree(r.value()).path("idempotencyKey").asString().equals(key))
        .count();
  }

  @Test
  void aRepeatAfterCompletionReturnsTheStoredBodyByteForByte() throws Exception {
    String key = "replay-" + UUID.randomUUID();
    MvcTestResult first = submit(key, OrchestratorStub.COMPLETES, "Zmlyc3QtY2lwaGVydGV4dA==");
    assertThat(first).hasStatus(200);

    // a fresh PIN encryption each time: not part of the content compared
    MvcTestResult repeat = submit(key, OrchestratorStub.COMPLETES, "c2Vjb25kLWNpcGhlcnRleHQ=");

    assertThat(repeat).hasStatus(200);
    assertThat(bytes(repeat)).isEqualTo(bytes(first));
    assertThat(published(key)).as("the repeat is not published again").isEqualTo(1);
    verify(auth, times(1)).authorise(any());
  }

  @Test
  void otherContentUnderAnAnsweredKeyIs409() throws Exception {
    String key = "replay-" + UUID.randomUUID();
    assertThat(submit(key, OrchestratorStub.COMPLETES, "Zmlyc3QtY2lwaGVydGV4dA==")).hasStatus(200);

    MvcTestResult other = submit(key, OrchestratorStub.COMPLETES + 1, "Zmlyc3QtY2lwaGVydGV4dA==");

    assertThat(other).hasStatus(409);
    assertThat(other).bodyJson().extractingPath("$.code").isEqualTo("MW-IDEM-001");
    assertThat(published(key)).isEqualTo(1);
  }
}
