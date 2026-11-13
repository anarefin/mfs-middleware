package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.TestTopics;
import mfs.mw.app.gateway.contracts.ContractSchemas;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The synchronous wait over real Kafka (spec 7.5): the gateway publishes the canonical request
 * with its {@code mw-reply-to}, a stub orchestrator answers on {@code mw.txn.status.v1}, and the
 * HTTP answer is 200 on a terminal status within the budget, 202 PROCESSING otherwise.
 */
@SpringBootTest(
    properties = {
      "mw.app-gateway.reply-budget=3s",
      "mw.app-gateway.instance-id=it-gw-1",
      "mw.app-gateway.consumers.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
class SyncReplyIT {

  static final String PAYER = "01711000001";
  static final String PAYEE = "01711000002";
  static final String UUID_V7 = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
  static final String CIPHERTEXT = "T0FFUC1jaXBoZXJ0ZXh0LW9wYXF1ZQ==";

  private static OrchestratorStub orchestrator;

  private final MockMvcTester mvc;
  private final JsonMapper json;

  @Autowired
  SyncReplyIT(MockMvcTester mvc, JsonMapper json) {
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
    TestTopics.awaitGroupStable("mw-app-gateway.reply.it-gw-1", Duration.ofSeconds(60));
  }

  static RequestPostProcessor user(String msisdn) {
    return jwt()
        .jwt(j -> j.claim("msisdn", msisdn).claim("realm_access", Map.of("roles", List.of("customer"))));
  }

  MvcTestResult submit(String key, long amount) {
    return mvc.post()
        .uri("/app/v1/transactions")
        .with(user(PAYER))
        .header("Idempotency-Key", key)
        .header("X-Correlation-Id", corr(key))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"txnType":"P2P","creditParty":{"id":"%s"},"amount":{"valueMinor":%d,"currency":"BDT"},
             "encryptedPin":{"keyId":"core-pin-1","ciphertext":"%s"},"locale":"bn"}
            """
                .formatted(PAYEE, amount, CIPHERTEXT))
        .exchange();
  }

  static String key() {
    return "it-" + UUID.randomUUID();
  }

  /**
   * A caller correlation id without digits: {@code CorrelationIdFilter} replaces a caller id that
   * {@code MaskingPolicy.scrub} would change, and a random UUID's digit groups sometimes read as an
   * MSISDN, which made this test fail at random.
   */
  static String corr(String key) {
    return "corr-" + key.replaceAll("[0-9]", "x");
  }

  JsonNode body(MvcTestResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  ConsumerRecord<String, String> requestFor(String key) {
    return orchestrator.requests().stream()
        .filter(r -> json.readTree(r.value()).path("idempotencyKey").asString().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void terminalStatusWithinTheBudgetIs200WithTheResult() throws Exception {
    String key = key();
    MvcTestResult r = submit(key, OrchestratorStub.COMPLETES);

    assertThat(r).hasStatus(200);
    JsonNode b = body(r);
    assertThat(b.path("txnId").asString()).matches(UUID_V7);
    assertThat(b.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(b.path("fee").path("valueMinor").asLong()).isEqualTo(500);
    assertThat(b.path("coreRef").asString()).isEqualTo("CORE-STUB-1");
    assertThat(b.path("amount").path("valueMinor").asLong()).isEqualTo(OrchestratorStub.COMPLETES);

    ConsumerRecord<String, String> published = requestFor(key);
    JsonNode req = json.readTree(published.value());
    assertThat(published.key()).isEqualTo(b.path("txnId").asString());
    assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json").validate(req))
        .isEmpty();
    assertThat(req.path("txnId").asString()).isEqualTo(b.path("txnId").asString());
    assertThat(req.path("channel").asString()).isEqualTo("CUSTOMER_APP");
    assertThat(req.path("txnType").asString()).isEqualTo("P2P");
    assertThat(req.path("correlationId").asString()).isEqualTo(corr(key));
    assertThat(req.path("initiator").path("id").asString()).isEqualTo(PAYER);
    assertThat(req.path("debitParty").path("id").asString()).isEqualTo(PAYER);
    assertThat(req.path("creditParty").path("id").asString()).isEqualTo(PAYEE);
    assertThat(req.path("encryptedPin").path("ciphertext").asString()).isEqualTo(CIPHERTEXT);
    assertThat(req.path("encryptedPin").path("keyId").asString()).isEqualTo("core-pin-1");
    assertThat(req.path("locale").asString()).isEqualTo("bn");
    assertThat(KafkaTestConsumer.headers(published))
        .containsEntry("mw-reply-to", "it-gw-1")
        .containsEntry("mw-idempotency-key", key)
        .containsEntry("mw-txn-id", b.path("txnId").asString())
        .containsEntry("mw-source", "mw-app-gateway");
  }

  @Tag("slow-wait")
  @Test
  void noReplyWithinTheBudgetIs202WithTheTxnIdAndGetShowsTheStatus() throws Exception {
    long start = System.nanoTime();
    MvcTestResult r = submit(key(), OrchestratorStub.SILENT);
    long tookMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(r).hasStatus(202);
    JsonNode b = body(r);
    String txnId = b.path("txnId").asString();
    assertThat(txnId).matches(UUID_V7);
    assertThat(b.path("status").asString()).isEqualTo("PROCESSING");
    assertThat(r.getResponse().getHeader("Location")).isEqualTo("/app/v1/transactions/" + txnId);
    assertThat(tookMs).isBetween(2_900L, 10_000L);

    MvcTestResult got = mvc.get().uri("/app/v1/transactions/" + txnId).with(user(PAYER)).exchange();
    assertThat(got).hasStatus(200);
    assertThat(body(got).path("status").asString()).isEqualTo("RECEIVED");
    assertThat(body(got).path("creditParty").path("id").asString()).isEqualTo(PAYEE);

    MvcTestResult other = mvc.get().uri("/app/v1/transactions/" + txnId).with(user("01711000099")).exchange();
    assertThat(other).hasStatus(404);
    assertThat(other).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
  }

  @Tag("slow-wait")
  @Test
  void aReplyForAnotherInstanceIsIgnored() throws Exception {
    MvcTestResult r = submit(key(), OrchestratorStub.OTHER_INSTANCE);
    assertThat(r).hasStatus(202);
    String txnId = body(r).path("txnId").asString();

    // the shared projection still sees it: GET shows the final status
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    String status = null;
    while (System.nanoTime() < deadline && !"COMPLETED".equals(status)) {
      status = body(mvc.get().uri("/app/v1/transactions/" + txnId).with(user(PAYER)).exchange()).path("status").asString();
      Thread.sleep(200);
    }
    assertThat(status).isEqualTo("COMPLETED");
  }

  @Test
  void coreRejectionIsTheEnvelopeWithTheTxnId() throws Exception {
    MvcTestResult r = submit(key(), OrchestratorStub.CORE_REJECTS);
    assertThat(r).hasStatus(422);
    JsonNode b = body(r);
    assertThat(b.path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(b.path("message").asString()).isEqualTo("Insufficient balance");
    assertThat(b.path("messageBn").asString()).isEqualTo("অপর্যাপ্ত ব্যালেন্স");
    assertThat(b.path("txnId").asString()).matches(UUID_V7);
    assertThat(b.path("retryable").asBoolean()).isFalse();
  }

  @Test
  void repeatedKeyAnswersWithTheStoredTransaction() throws Exception {
    MvcTestResult done = submit(key(), OrchestratorStub.DUPLICATE_DONE);
    assertThat(done).hasStatus(200);
    assertThat(body(done).path("txnId").asString()).isEqualTo(OrchestratorStub.STORED_TXN_ID);
    assertThat(body(done).path("status").asString()).isEqualTo("COMPLETED");

    MvcTestResult running = submit(key(), OrchestratorStub.DUPLICATE_RUNNING);
    assertThat(running).hasStatus(202);
    assertThat(body(running).path("txnId").asString()).isEqualTo(OrchestratorStub.STORED_TXN_ID);
    assertThat(body(running).path("status").asString()).isEqualTo("PROCESSING");
  }

  /**
   * S3: the answer to a repeated key comes after the reply budget, so the 202 carries the txnId
   * minted for the repeat. That id must not stay RECEIVED for ever: once the duplicate answer
   * names the stored transaction, GET on it shows the stored transaction, up to its final status.
   */
  @Tag("slow-wait")
  @Test
  void aRepeatAnsweredAfterTheBudgetGives202WithAnIdThatReachesTheFinalStatus() throws Exception {
    String key = key();
    MvcTestResult first = submit(key, OrchestratorStub.DUPLICATE_SLOW);
    assertThat(first).hasStatus(202);
    String stored = body(first).path("txnId").asString();

    MvcTestResult repeat = submit(key, OrchestratorStub.DUPLICATE_SLOW);
    assertThat(repeat).hasStatus(202);
    String minted = body(repeat).path("txnId").asString();
    assertThat(minted).matches(UUID_V7).isNotEqualTo(stored);

    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    JsonNode view = null;
    while (System.nanoTime() < deadline) {
      MvcTestResult got = mvc.get().uri("/app/v1/transactions/" + minted).with(user(PAYER)).exchange();
      assertThat(got).hasStatus(200);
      view = body(got);
      if ("COMPLETED".equals(view.path("status").asString())) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(view.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(view.path("txnId").asString()).isEqualTo(stored);
    assertThat(view.path("coreRef").asString()).isEqualTo("CORE-STUB-1");

    MvcTestResult other = mvc.get().uri("/app/v1/transactions/" + minted).with(user("01711000099")).exchange();
    assertThat(other).hasStatus(404);
  }

  @Test
  void sameKeyOtherContentIs409() throws Exception {
    MvcTestResult r = submit(key(), OrchestratorStub.IDEM_CONFLICT);
    assertThat(r).hasStatus(409);
    assertThat(body(r).path("code").asString()).isEqualTo("MW-IDEM-001");
  }
}
