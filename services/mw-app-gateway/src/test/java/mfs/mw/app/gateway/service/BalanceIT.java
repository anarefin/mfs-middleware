package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.TestTopics;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.contracts.ContractSchemas;
import mfs.mw.app.gateway.dto.TxnAuthorisation;
import mfs.mw.app.gateway.messaging.KafkaTestConsumer;
import mfs.mw.app.gateway.messaging.OrchestratorStub;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /app/v1/balance} over real Kafka (ADR D26): the gateway publishes a BALANCE request
 * with its {@code mw-reply-to}, a stub orchestrator answers on {@code mw.txn.status.v1} with the
 * core's balance, and the HTTP answer is 200 with it; nothing is kept in the app history.
 */
@SpringBootTest(
    properties = {
      "mw.app-gateway.reply-budget=3s",
      "mw.app-gateway.instance-id=it-gw-bal",
      "mw.app-gateway.consumers.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
class BalanceIT {

  static final String WALLET = "01711000001";
  static final String CIPHERTEXT = "T0FFUC1jaXBoZXJ0ZXh0LW9wYXF1ZQ==";
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJiYWwifQ.c2lnbmF0dXJl";

  private static OrchestratorStub orchestrator;

  private final MockMvcTester mvc;
  private final JsonMapper json;

  @MockitoBean AuthClient auth;

  @Autowired
  BalanceIT(MockMvcTester mvc, JsonMapper json) {
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
  void replyConsumerReady() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, Instant.now().plusSeconds(60), false));
    TestTopics.awaitGroupStable("mw-app-gateway.reply.it-gw-bal", Duration.ofSeconds(60));
  }

  static RequestPostProcessor as(String role) {
    return jwt().jwt(j -> j.claim("msisdn", WALLET).claim("role", role).claim("amr", List.of("pin", "device")));
  }

  MvcTestResult check(String role, String key) {
    return mvc.post()
        .uri("/app/v1/balance")
        .with(as(role))
        .header("Idempotency-Key", key)
        .header("X-Correlation-Id", "corr-balance-it")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":{\"keyId\":\"PIN_TRANSPORT.v1\",\"ciphertext\":\"%s\"},\"locale\":\"bn\"}"
            .formatted(CIPHERTEXT))
        .exchange();
  }

  static String key(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  JsonNode body(MvcTestResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  ConsumerRecord<String, String> requestFor(String key) {
    return orchestrator.requests().stream()
        .filter(r -> json.readTree(r.value()).path("idempotencyKey").asString().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void theCoresBalanceIs200AndTheRequestIsAContractBalanceOfTheOwnWallet() throws Exception {
    String key = key("balance-ok");
    MvcTestResult r = check("agent", key);

    assertThat(r).hasStatus(200);
    JsonNode b = body(r);
    assertThat(b.path("available").path("valueMinor").asLong()).isEqualTo(OrchestratorStub.BALANCE_AVAILABLE);
    assertThat(b.path("available").path("currency").asString()).isEqualTo("BDT");
    assertThat(b.path("asOf").asString()).isEqualTo("2026-11-16T08:15:31Z");
    String txnId = b.path("txnId").asString();

    ConsumerRecord<String, String> published = requestFor(key);
    JsonNode req = json.readTree(published.value());
    assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json").validate(req)).isEmpty();
    assertThat(published.key()).isEqualTo(txnId);
    assertThat(req.path("txnType").asString()).isEqualTo("BALANCE");
    assertThat(req.path("channel").asString()).isEqualTo("AGENT_APP");
    assertThat(req.path("amount").path("valueMinor").asLong()).isZero();
    assertThat(req.path("debitParty").path("id").asString()).isEqualTo(WALLET);
    assertThat(req.path("debitParty").path("walletType").asString()).isEqualTo("AGENT");
    assertThat(req.path("creditParty")).isEqualTo(req.path("debitParty"));
    assertThat(req.path("assertion").asString()).isEqualTo(ASSERTION);
    assertThat(published.value()).doesNotContain(CIPHERTEXT);
    assertThat(KafkaTestConsumer.headers(published)).containsEntry("mw-reply-to", "it-gw-bal");

    // never in the app's own projection: GET /app/v1/transactions/{txnId} does not know it
    assertThat(mvc.get().uri("/app/v1/transactions/" + txnId).with(as("agent")).exchange()).hasStatus(404);
  }

  @Test
  void aCoreRefusalIs422WithTheTxnId() throws Exception {
    MvcTestResult r = check("customer", key(OrchestratorStub.BALANCE_REJECTED));

    assertThat(r).hasStatus(422);
    JsonNode b = body(r);
    assertThat(b.path("code").asString()).isEqualTo("MW-CORE-WALLET_NOT_ACTIVE");
    assertThat(b.path("txnId").asString()).isNotBlank();
  }

  @Tag("slow-wait")
  @Test
  void noAnswerWithinTheBudgetIs504NeverA202() throws Exception {
    MvcTestResult r = check("merchant", key(OrchestratorStub.BALANCE_SILENT));

    assertThat(r).hasStatus(504);
    JsonNode b = body(r);
    assertThat(b.path("code").asString()).isEqualTo("MW-TMO-001");
    assertThat(b.path("retryable").asBoolean()).isTrue();
    assertThat(b.path("txnId").asString()).isNotBlank();
  }
}
