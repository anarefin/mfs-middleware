package mfs.mw.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.entity.IpnDelivery;
import mfs.mw.checkout.messaging.EventPublisher;
import mfs.mw.checkout.messaging.OrchestratorStub;
import mfs.mw.checkout.repository.IpnDeliveryRepository;
import mfs.mw.checkout.repository.SessionRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Merchant refunds end to end inside the module (ticket 116, FRS CH-WEB-03, FL-06, UC-12): the merchant
 * API as APISIX forwards it, real PostgreSQL and Kafka, the REVERSAL on {@code mw.txn.request.v1} and a
 * stub playing the orchestrator and the core's refunded total ({@link OrchestratorStub}). The session
 * is made PAID in the database (the pay path is {@link CheckoutFlowIT}'s).
 */
@SpringBootTest(
    properties = {
      "mw.checkout.instance-id=it-refund-1",
      "mw.checkout.consumers.enabled=true",
      "mw.checkout.reply-budget=5s"
    })
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutRefundIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String MERCHANT = "merchant_demo";
  static final String MERCHANT_WALLET = "01911000001";
  static final String PAYER = "8801711000001";

  private final MockMvcTester http;
  private final SessionRepository sessions;
  private final IpnDeliveryRepository deliveries;
  private final JdbcClient jdbc;
  private OrchestratorStub orchestrator;

  @MockitoSpyBean
  EventPublisher publisher;

  @Autowired
  CheckoutRefundIT(
      MockMvcTester http, SessionRepository sessions, IpnDeliveryRepository deliveries, JdbcClient jdbc) {
    this.http = http;
    this.sessions = sessions;
    this.deliveries = deliveries;
    this.jdbc = jdbc;
  }

  @BeforeAll
  void startStub() {
    TestTopics.ensure("mw.txn.request.v1", "mw.txn.status.v1");
    orchestrator = new OrchestratorStub(TestTopics.bootstrap());
  }

  @BeforeEach
  void consumersReady() {
    TestTopics.awaitGroupStable("mw-checkout.reply.it-refund-1", Duration.ofSeconds(60));
    TestTopics.awaitGroupStable("mw-checkout", Duration.ofSeconds(60));
  }

  @AfterAll
  void stopStub() {
    orchestrator.close();
  }

  @Test
  void twoPartialRefundsWithinThePaymentSucceedAndAThirdOverItIsRefused() throws Exception {
    Paid p = paid(10_000);

    MvcTestResult first = refund(MERCHANT, p.sessionId(), "r-" + UUID.randomUUID(), 6_000);
    assertThat(first).hasStatus(201);
    JsonNode f = body(first);
    assertThat(f.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(f.path("amount").path("valueMinor").asLong()).isEqualTo(6_000);
    assertThat(f.path("originalTxnId").asString()).isEqualTo(p.txnId());
    assertThat(f.path("refundId").asString()).isNotEqualTo(p.txnId());

    assertThat(refund(MERCHANT, p.sessionId(), "r-" + UUID.randomUUID(), 4_000)).hasStatus(201);

    MvcTestResult third = refund(MERCHANT, p.sessionId(), "r-" + UUID.randomUUID(), 1);
    assertThat(third).hasStatus(422);
    assertThat(body(third).path("code").asString()).isEqualTo("MW-CORE-REFUND_EXCEEDS");

    JsonNode list = body(http.get().uri("/checkout/v1/payments/{id}/refunds", p.sessionId())
        .header("X-Mw-Merchant-Id", MERCHANT).exchange());
    assertThat(list.path("items")).hasSize(3);
    assertThat(list.path("items").get(2).path("status").asString()).isEqualTo("REJECTED");
    assertThat(list.path("items").get(2).path("errorCode").asString()).isEqualTo("MW-CORE-REFUND_EXCEEDS");
    assertThat(list.path("refunded").path("valueMinor").asLong()).isEqualTo(10_000);

    // the REVERSAL as the orchestrator's refund flow takes it: the payment's parties reversed
    JsonNode req = JSON.readTree(requestsOf(f.path("refundId").asString()).getFirst().value());
    assertThat(req.path("channel").asString()).isEqualTo("WEB_CHECKOUT");
    assertThat(req.path("txnType").asString()).isEqualTo("REVERSAL");
    assertThat(req.path("initiator").path("type").asString()).isEqualTo("MERCHANT");
    assertThat(req.path("initiator").path("id").asString()).isEqualTo(MERCHANT);
    assertThat(req.path("debitParty").path("id").asString()).isEqualTo(MERCHANT_WALLET);
    assertThat(req.path("debitParty").path("walletType").asString()).isEqualTo("MERCHANT");
    assertThat(req.path("creditParty").path("id").asString()).isEqualTo(PAYER);
    assertThat(req.path("creditParty").path("walletType").asString()).isEqualTo("CUSTOMER");
    assertThat(req.path("originalTxnId").asString()).isEqualTo(p.txnId());
    assertThat(req.has("assertion")).isFalse();

    // one signed refund IPN per finished refund, keyed by the refundId
    List<IpnDelivery> ipn = deliveries.findByRefund(UUID.fromString(f.path("refundId").asString()));
    assertThat(ipn).hasSize(1);
    JsonNode payload = JSON.readTree(ipn.getFirst().payload());
    assertThat(payload.path("type").asString()).isEqualTo("REFUND");
    assertThat(payload.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(payload.path("amount").path("valueMinor").asLong()).isEqualTo(6_000);
    assertThat(ipn.getFirst().ipnId()).isEqualTo(ipn.getFirst().refundId());
    assertThat(deliveries.findBySession(UUID.fromString(p.sessionId()))).isEmpty();
  }

  @Test
  void theSameIdempotencyKeyTwiceGivesOneRefundAndAnotherBodyIsAConflict() throws Exception {
    Paid p = paid(5_000);
    String key = "r-" + UUID.randomUUID();
    MvcTestResult first = refund(MERCHANT, p.sessionId(), key, 2_000);
    assertThat(first).hasStatus(201);
    MvcTestResult again = refund(MERCHANT, p.sessionId(), key, 2_000);
    assertThat(again).hasStatus(200);
    assertThat(body(again).path("refundId").asString()).isEqualTo(body(first).path("refundId").asString());
    assertThat(requestsOf(body(first).path("refundId").asString())).hasSize(1);

    MvcTestResult other = refund(MERCHANT, p.sessionId(), key, 2_500);
    assertThat(other).hasStatus(409);
    assertThat(body(other).path("code").asString()).isEqualTo("MW-IDEM-001");
    assertThat(jdbc.sql("select count(*) from refund where session_id = ?")
        .param(UUID.fromString(p.sessionId())).query(Long.class).single()).isEqualTo(1);
  }

  /**
   * M9 review (FL-06): Kafka stored the REVERSAL but the send timed out. The refund row stays PENDING
   * (never deleted), the orchestrator's final status settles it, and the merchant's retry with the
   * same key answers that refund: counted, one IPN, one request.
   */
  @Test
  void aRefundWhoseSendTimedOutAfterKafkaStoredItIsSettledNotLost() throws Exception {
    Paid p = paid(5_000);
    String key = "r-" + UUID.randomUUID();
    doAnswer(inv -> {
      inv.callRealMethod();
      throw new IllegalStateException("ack timed out");
    }).when(publisher).publish(eq("mw.txn.request.v1"), anyString(), any(), any(), anyMap());
    try {
      assertThat(refund(MERCHANT, p.sessionId(), key, 2_000)).hasStatus(503);
    } finally {
      reset(publisher);
    }
    for (long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        !"COMPLETED".equals(refundStatus(p.sessionId())) && System.nanoTime() < end; ) {
      Thread.sleep(50);
    }

    MvcTestResult retry = refund(MERCHANT, p.sessionId(), key, 2_000);
    assertThat(retry).hasStatus(200);
    assertThat(body(retry).path("status").asString()).isEqualTo("COMPLETED");
    String refundId = body(retry).path("refundId").asString();
    assertThat(requestsOf(refundId)).hasSize(1);
    assertThat(deliveries.findByRefund(UUID.fromString(refundId))).hasSize(1);
    assertThat(body(http.get().uri("/checkout/v1/payments/{id}/refunds", p.sessionId())
        .header("X-Mw-Merchant-Id", MERCHANT).exchange()).path("refunded").path("valueMinor").asLong()).isEqualTo(2_000);
  }

  /**
   * M9 review (FL-06): Kafka refused the REVERSAL (nothing stored). The row stays PENDING and the
   * merchant's retry with the same key sends the same refund (same refundId, same key) again.
   */
  @Test
  void aRefundKafkaRefusedIsSentAgainUnderTheSameRefundIdOnTheRetry() throws Exception {
    Paid p = paid(5_000);
    String key = "r-" + UUID.randomUUID();
    doThrow(new IllegalStateException("kafka down"))
        .when(publisher).publish(eq("mw.txn.request.v1"), anyString(), any(), any(), anyMap());
    try {
      assertThat(refund(MERCHANT, p.sessionId(), key, 2_000)).hasStatus(503);
    } finally {
      reset(publisher);
    }
    assertThat(refundStatus(p.sessionId())).isEqualTo("PENDING");
    String refundId = jdbc.sql("select refund_id from refund where session_id = ?")
        .param(UUID.fromString(p.sessionId())).query(UUID.class).single().toString();

    MvcTestResult retry = refund(MERCHANT, p.sessionId(), key, 2_000);
    assertThat(retry).hasStatus(200);
    assertThat(body(retry).path("refundId").asString()).isEqualTo(refundId);
    assertThat(body(retry).path("status").asString()).isEqualTo("COMPLETED");
    assertThat(requestsOf(refundId)).hasSize(1);
    assertThat(deliveries.findByRefund(UUID.fromString(refundId))).hasSize(1);
  }

  @Test
  void noAmountRefundsTheWholePayment() throws Exception {
    Paid p = paid(3_000);
    MvcTestResult r = http.post().uri("/checkout/v1/payments/{id}/refunds", p.sessionId())
        .header("X-Mw-Merchant-Id", MERCHANT).header("Idempotency-Key", "r-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
    assertThat(r).hasStatus(201);
    assertThat(body(r).path("amount").path("valueMinor").asLong()).isEqualTo(3_000);
  }

  @Test
  void anotherMerchantCanNeitherRefundNorListThePayment() throws Exception {
    Paid p = paid(5_000);
    MvcTestResult post = refund("merchant_other", p.sessionId(), "r-" + UUID.randomUUID(), 1_000);
    assertThat(post).hasStatus(403);
    assertThat(body(post).path("code").asString()).isEqualTo("MW-AUTH-022");
    MvcTestResult get = http.get().uri("/checkout/v1/payments/{id}/refunds", p.sessionId())
        .header("X-Mw-Merchant-Id", "merchant_other").exchange();
    assertThat(get).hasStatus(403);
    assertThat(body(get).path("code").asString()).isEqualTo("MW-AUTH-022");
    assertThat(jdbc.sql("select count(*) from refund where session_id = ?")
        .param(UUID.fromString(p.sessionId())).query(Long.class).single()).isZero();
  }

  @Test
  void anUnpaidSessionIsNotRefundedAndTheKeyIsRequired() throws Exception {
    CheckoutSession open = session(2_000);
    MvcTestResult r = refund(MERCHANT, open.sessionId().toString(), "r-" + UUID.randomUUID(), 1_000);
    assertThat(r).hasStatus(409);
    assertThat(body(r).path("code").asString()).isEqualTo("MW-VAL-021");

    Paid p = paid(2_000);
    MvcTestResult noKey = http.post().uri("/checkout/v1/payments/{id}/refunds", p.sessionId())
        .header("X-Mw-Merchant-Id", MERCHANT).contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
    assertThat(noKey).hasStatus(400);
    assertThat(body(noKey).path("code").asString()).isEqualTo("MW-VAL-001");

    MvcTestResult unknownField = http.post().uri("/checkout/v1/payments/{id}/refunds", p.sessionId())
        .header("X-Mw-Merchant-Id", MERCHANT).header("Idempotency-Key", "r-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":{\"valueMinor\":0,\"currency\":\"BDT\"}}")
        .exchange();
    assertThat(unknownField).hasStatus(400);
  }

  // ---- helpers ------------------------------------------------------------------------------

  record Paid(String sessionId, String txnId) {}

  private CheckoutSession session(long amount) {
    Instant now = Instant.now();
    CheckoutSession s = new CheckoutSession(
        UUID.randomUUID(), "tok-" + UUID.randomUUID(), MERCHANT, MERCHANT_WALLET, "ORD-" + UUID.randomUUID(),
        amount, "BDT", null, "en", "https://shop.example.test/ok", "https://shop.example.test/fail",
        "https://shop.example.test/cancel", "https://shop.example.test/ipn", SessionStatus.OPEN, null, 0, null, null,
        now, now.plus(Duration.ofMinutes(15)), now);
    assertThat(sessions.insert(s)).isTrue();
    return s;
  }

  private Paid paid(long amount) {
    CheckoutSession s = session(amount);
    UUID txnId = UUID.randomUUID();
    jdbc.sql("update session set status = 'PAID', txn_id = ?, payer_msisdn = ? where session_id = ?")
        .params(txnId, PAYER, s.sessionId())
        .update();
    orchestrator.paid(txnId.toString(), amount);
    return new Paid(s.sessionId().toString(), txnId.toString());
  }

  private MvcTestResult refund(String merchant, String paymentId, String key, long amount) {
    return http.post().uri("/checkout/v1/payments/{id}/refunds", paymentId)
        .header("X-Mw-Merchant-Id", merchant)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"amount\":{\"valueMinor\":" + amount + ",\"currency\":\"BDT\"},\"reason\":\"returned\"}")
        .exchange();
  }

  private String refundStatus(String sessionId) {
    return jdbc.sql("select status from refund where session_id = ?")
        .param(UUID.fromString(sessionId)).query(String.class).single();
  }

  private List<ConsumerRecord<String, String>> requestsOf(String txnId) {
    return orchestrator.requests().stream().filter(r -> txnId.equals(r.key())).toList();
  }

  static JsonNode body(MvcTestResult r) throws Exception {
    return JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
