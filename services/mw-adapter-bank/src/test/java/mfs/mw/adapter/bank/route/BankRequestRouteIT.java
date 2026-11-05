package mfs.mw.adapter.bank.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.bank.BankFixtures.newTxnId;
import static mfs.mw.adapter.bank.BankFixtures.expiredPay;
import static mfs.mw.adapter.bank.BankFixtures.pay;
import static mfs.mw.adapter.bank.BankFixtures.statusQuery;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import mfs.mw.adapter.bank.TestInfrastructure;
import mfs.mw.adapter.bank.contracts.ContractSchemas;
import mfs.mw.adapter.bank.dto.PartnerReply;
import mfs.mw.adapter.bank.dto.PartnerRequest;
import mfs.mw.adapter.bank.messaging.EventMetadata;
import mfs.mw.adapter.bank.messaging.EventPublisher;
import mfs.mw.adapter.bank.messaging.KafkaTestConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Route tests (ticket 16): a canonical top-up request on {@code mw.partner.request.bank.v1}
 * reaches the bank (WireMock, wire format of mw-simulators) and every outcome comes back on {@code
 * mw.partner.reply.v1} with the right status and code, valid against the contract. Real Kafka and
 * PostgreSQL (Testcontainers). Time limiter 1.5 s and a 4-call circuit window keep it quick.
 */
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.bank.request-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "mw.partners.instances.c2wbank.read-timeout-ms=4000",
      "mw.partners.instances.c2wbank.timeout-ms=1500",
      "resilience4j.circuitbreaker.instances.c2wbank.sliding-window-type=COUNT_BASED",
      "resilience4j.circuitbreaker.instances.c2wbank.sliding-window-size=4",
      "resilience4j.circuitbreaker.instances.c2wbank.minimum-number-of-calls=4",
      "resilience4j.circuitbreaker.instances.c2wbank.failure-rate-threshold=50"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class BankRequestRouteIT {

  static final String REQUEST_TOPIC = "mw.partner.request.bank.v1";
  static final String REPLY_TOPIC = "mw.partner.reply.v1";
  static final String REPLY_SCHEMA = "events/mw-orchestrator/mw.partner.reply.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String AUDIT_TOPIC = "mw.audit.event.v1";
  static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  static final WireMockServer BANK =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    BANK.start();
  }

  @DynamicPropertySource
  static void bankUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.c2wbank.base-url", () -> "http://localhost:" + BANK.port());
  }

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JdbcTemplate jdbc;
  private final CircuitBreakerRegistry breakers;

  @Autowired
  BankRequestRouteIT(
      EventPublisher publisher,
      KafkaContainer kafka,
      JdbcTemplate jdbc,
      CircuitBreakerRegistry breakers) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.jdbc = jdbc;
    this.breakers = breakers;
  }

  @BeforeEach
  void reset() {
    BANK.resetAll();
    breakers.circuitBreaker("c2wbank").reset();
  }

  @Test
  void successIsRepliedWithTheBankReferenceAndTheStablePartnerReference() throws Exception {
    stubBank(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"message\":\"ok\",\"debitRef\":\"C2W-778899\"}");
    PartnerRequest request = pay(newTxnId(), "1234567890123", 2000);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.requestId()).isEqualTo(request.requestId());
    assertThat(reply.partnerId()).isEqualTo("c2wbank");
    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("C2W-778899");
    assertThat(reply.payload().partnerCode()).isEqualTo("0000");
    String ref = "UPAY" + request.txnId().replace("-", "").toUpperCase();
    assertThat(reply.payload().attributes()).containsEntry("partnerTxnRef", ref);
    BANK.verify(
        1,
        postRequestedFor(urlEqualTo("/c2wbank/v1/debits"))
            .withRequestBody(
                equalToJson(
                    "{\"transactionId\":\"" + ref + "\",\"accountNumber\":\"1234567890123\",\"amount\":\"20.00\"}")));
  }

  /**
   * S6 (spec 12.2, 17): the framework wrapper audits every partner call on mw.audit.event.v1
   * through the outbox, masked (the raw personal value never appears), within the audit contract.
   */
  @Test
  void everyPartnerCallIsAuditedMaskedOnTheAuditTopic() throws Exception {
    stubBank(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"message\":\"ok\",\"debitRef\":\"C2W-778899\"}");
    PartnerRequest request = pay(newTxnId(), "1234567890123", 2000);

    send(request);
    awaitReplies(request.txnId(), 1);

    List<ConsumerRecord<String, String>> audits =
        KafkaTestConsumer.read(
            kafka.getBootstrapServers(), AUDIT_TOPIC, 1, Duration.ofSeconds(30), r -> request.txnId().equals(r.key()));
    assertThat(audits).hasSize(1);
    tools.jackson.databind.JsonNode audit = JSON.readTree(audits.getFirst().value());
    assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(audit)).as(audit.toString()).isEmpty();
    assertThat(audit.path("action").asString()).isEqualTo("partner.call.completed");
    assertThat(audit.path("txnId").asString()).isEqualTo(request.txnId());
    assertThat(audit.path("correlationId").asString()).isEqualTo(request.correlationId());
    assertThat(audit.path("actor").path("type").asString()).isEqualTo("system");
    assertThat(audit.path("actor").path("id").asString()).isEqualTo("mw-adapter-bank");
    assertThat(audit.path("service").asString()).isEqualTo("mw-adapter-bank");
    assertThat(audit.path("resource").asString()).isEqualTo("partner/c2wbank/PAY");
    assertThat(audit.path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(audit.path("after").path("httpStatus").asString()).isEqualTo("200");
    assertThat(audit.path("after").path("partnerOutcome").asString()).isEqualTo("SUCCESS");
    assertThat(audit.path("after").path("request").asString()).contains("transactionId");
    assertThat(audit.path("after").path("response").asString()).contains("0000");
    assertThat(audit.toString()).doesNotContain("1234567890123");
    assertThat(KafkaTestConsumer.headers(audits.getFirst())).containsEntry("mw-source", "mw-adapter-bank");
  }

  @Test
  void aBusinessFailureIsFailedWithTheMappedPartnerCode() throws Exception {
    stubBank(422, "{\"status\":\"FAILED\",\"code\":\"4002\",\"message\":\"Debit refused\"}");
    PartnerRequest request = pay(newTxnId(), "01711000999", 2000);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-C2WBANK-DEBIT_REFUSED");
    assertThat(reply.payload().partnerCode()).isEqualTo("4002");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void aReplyLaterThanTheTimeLimiterIsUnknown() throws Exception {
    BANK.stubFor(
        post(urlEqualTo("/c2wbank/v1/debits")).willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));
    PartnerRequest request = pay(newTxnId(), "01711000113", 2013);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_TIMEOUT");
  }

  @Test
  void aServerErrorAfterSendIsUnknown() throws Exception {
    stubBank(500, "{\"status\":\"ERROR\"}");
    PartnerRequest request = pay(newTxnId(), "1234567890123", 2077);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_HTTP_5XX");
  }

  @Test
  void conflictAndTooManyRequestsAreUnknownNotFailed() throws Exception {
    for (int status : new int[] {409, 429}) {
      BANK.resetAll();
      stubBank(status, "{\"status\":\"FAILED\",\"code\":\"4002\"}");
      PartnerRequest request = pay(newTxnId(), "1234567890123", 2000);

      send(request);
      PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

      assertThat(reply.payload().outcome()).as("HTTP %s", status).isEqualTo("UNKNOWN");
      assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_UNEXPECTED_STATUS");
      assertThat(reply.payload().retryable()).isFalse();
    }
  }

  @Test
  void aConnectionResetAfterSendIsUnknown() throws Exception {
    BANK.stubFor(
        post(urlEqualTo("/c2wbank/v1/debits")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    PartnerRequest request = pay(newTxnId(), "1234567890123", 2000);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_CONNECTION_RESET");
  }

  @Test
  void anAmbiguousBankCodeIsUnknown() throws Exception {
    stubBank(422, "{\"status\":\"FAILED\",\"code\":\"9876\",\"message\":\"???\"}");
    PartnerRequest request = pay(newTxnId(), "1234567890123", 2099);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_AMBIGUOUS_CODE");
    assertThat(reply.payload().partnerCode()).isEqualTo("9876");
  }

  @Test
  void aMalformedBankReplyIsUnknown() throws Exception {
    stubBank(200, "<<garbage>>");
    PartnerRequest request = pay(newTxnId(), "1234567890123", 2055);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_MALFORMED_REPLY");
  }

  @Test
  void aRequestThatCannotBeMappedIsFailedWithoutCallingTheBank() throws Exception {
    PartnerRequest request = pay(newTxnId(), "1234567890123", 0);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-VAL-INVALID_PARTNER_REQUEST");
    BANK.verify(0, postRequestedFor(urlEqualTo("/c2wbank/v1/debits")));
  }

  @Test
  void aDuplicateRequestForTheSameTxnIdReusesThePartnerReference() throws Exception {
    stubBank(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"debitRef\":\"C2W-1\"}");
    String txnId = newTxnId();

    send(pay(txnId, "1234567890123", 2000));
    awaitReplies(txnId, 1);
    send(pay(txnId, "1234567890123", 2000));
    List<PartnerReply> replies = awaitReplies(txnId, 2);

    List<LoggedRequest> calls = BANK.findAll(postRequestedFor(urlEqualTo("/c2wbank/v1/debits")));
    assertThat(calls).hasSize(2);
    String first = JSON.readTree(calls.get(0).getBodyAsString()).get("transactionId").asString();
    String second = JSON.readTree(calls.get(1).getBodyAsString()).get("transactionId").asString();
    assertThat(second).isEqualTo(first).startsWith("UPAY");
    assertThat(replies)
        .extracting(r -> r.payload().attributes().get("partnerTxnRef"))
        .containsOnly(first);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from partner_reference where txn_id = ?", Integer.class, txnId))
        .isEqualTo(1);
  }

  @Test
  void aRedeliveredRequestIsRepliedToOnlyOnce() throws Exception {
    stubBank(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"debitRef\":\"C2W-1\"}");
    PartnerRequest request = pay(newTxnId(), "1234567890123", 2000);

    send(request);
    awaitReplies(request.txnId(), 1);
    send(request);
    Thread.sleep(3000);

    assertThat(replies(request.txnId(), 2, Duration.ofSeconds(2))).hasSize(1);
  }

  @Test
  void aFailedTopupIsNotRetried() throws Exception {
    stubBank(500, "{}");
    PartnerRequest request = pay(newTxnId(), "1234567890123", 2000);

    send(request);
    awaitReplies(request.txnId(), 1);
    Thread.sleep(2000);

    BANK.verify(1, postRequestedFor(urlEqualTo("/c2wbank/v1/debits")));
    assertThat(
            KafkaTestConsumer.read(
                kafka.getBootstrapServers(), RetryTopicRouteBuilder.retryTopic(REQUEST_TOPIC, "mw-adapter-bank", 1), 1, Duration.ofSeconds(1),
                r -> request.txnId().equals(r.key())))
        .isEmpty();
  }

  @Test
  void onceTheCircuitIsOpenRequestsAreFailedFastWithoutCallingTheBank() throws Exception {
    stubBank(500, "{}");
    for (int i = 0; i < 4; i++) {
      PartnerRequest request = pay(newTxnId(), "1234567890123", 2000);
      send(request);
      assertThat(awaitReplies(request.txnId(), 1).getFirst().payload().outcome()).isEqualTo("UNKNOWN");
    }

    PartnerRequest next = pay(newTxnId(), "1234567890123", 2000);
    send(next);
    PartnerReply reply = awaitReplies(next.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-SYS-PARTNER_CIRCUIT_OPEN");
    assertThat(reply.payload().retryable()).isTrue();
    BANK.verify(4, postRequestedFor(urlEqualTo("/c2wbank/v1/debits")));
  }

  // --- status inquiry (ticket 18, spec 7.4) ------------------------------------------------------

  private static String ref(String txnId) {
    return "UPAY" + txnId.replace("-", "").toUpperCase();
  }

  private void stubStatus(String txnId, int status, String body) {
    BANK.stubFor(
        get(urlEqualTo("/c2wbank/v1/debits/" + ref(txnId)))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void aStatusQueryAsksTheBankWithTheStableReferenceAndReportsTheTrueOutcome() throws Exception {
    PartnerRequest request = statusQuery(newTxnId());
    stubStatus(request.txnId(), 200,
        "{\"transactionId\":\"" + ref(request.txnId()) + "\",\"status\":\"SUCCESS\",\"code\":\"0000\",\"debitRef\":\"C2W-55\"}");

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.requestId()).isEqualTo(request.requestId());
    assertThat(reply.payload().operation()).isEqualTo("STATUS_QUERY");
    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("C2W-55");
    BANK.verify(1, getRequestedFor(urlEqualTo("/c2wbank/v1/debits/" + ref(request.txnId()))));
    BANK.verify(0, postRequestedFor(urlEqualTo("/c2wbank/v1/debits")));
  }

  @Test
  void aStatusQueryForADebitTheBankNeverSawIsNotFound() throws Exception {
    PartnerRequest request = statusQuery(newTxnId());
    stubStatus(request.txnId(), 404, "{\"status\":\"NOT_FOUND\",\"code\":\"4040\",\"message\":\"Unknown transaction\"}");

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("NOT_FOUND");
    assertThat(reply.payload().partnerCode()).isEqualTo("4040");
  }

  @Test
  void aStatusQueryForAFailedDebitIsFailedWithTheMappedCode() throws Exception {
    PartnerRequest request = statusQuery(newTxnId());
    stubStatus(request.txnId(), 200, "{\"status\":\"FAILED\",\"code\":\"4002\",\"message\":\"barred\"}");

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-C2WBANK-DEBIT_REFUSED");
  }

  @Test
  void aStatusQueryWithoutADefiniteAnswerIsUnknownNeverFailed() throws Exception {
    for (int status : new int[] {503, 404}) {
      BANK.resetAll();
      PartnerRequest request = statusQuery(newTxnId());
      // a 404 without the bank's NOT_FOUND body (a proxy, a wrong path) is not an answer either
      stubStatus(request.txnId(), status, status == 404 ? "<html>no route</html>" : "{\"status\":\"UNAVAILABLE\"}");

      send(request);
      PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

      assertThat(reply.payload().outcome()).as("HTTP %s", status).isEqualTo("UNKNOWN");
    }
  }

  @Test
  void aPayPastItsDeadlineIsRefusedWithoutCallingTheBank() throws Exception {
    PartnerRequest request = expiredPay(newTxnId(), "1234567890123", 2000);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-SYS-PARTNER_REQUEST_EXPIRED");
    BANK.verify(0, anyRequestedFor(anyUrl()));
  }

  private void stubBank(int status, String body) {
    BANK.stubFor(
        post(urlEqualTo("/c2wbank/v1/debits"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private void send(PartnerRequest request) {
    publisher.publish(
        REQUEST_TOPIC,
        request.txnId(),
        new EventMetadata(request.correlationId(), request.txnId(), request.requestId(), 1),
        request);
  }

  private List<PartnerReply> awaitReplies(String txnId, int count) {
    List<PartnerReply> replies = replies(txnId, count, Duration.ofSeconds(30));
    assertThat(replies).as("replies for %s", txnId).hasSize(count);
    return replies;
  }

  /** Reads replies for {@code txnId}; every one is checked against the contract schema. */
  private List<PartnerReply> replies(String txnId, int count, Duration timeout) {
    List<ConsumerRecord<String, String>> records =
        KafkaTestConsumer.read(
            kafka.getBootstrapServers(), REPLY_TOPIC, count, timeout, r -> txnId.equals(r.key()));
    return records.stream()
        .map(
            r -> {
              assertThat(ContractSchemas.schema(REPLY_SCHEMA).validate(JSON.readTree(r.value())))
                  .as("reply %s", r.value())
                  .isEmpty();
              assertThat(KafkaTestConsumer.headers(r))
                  .containsEntry("mw-txn-id", txnId)
                  .containsEntry("mw-source", "mw-adapter-bank");
              return JSON.readValue(r.value(), PartnerReply.class);
            })
        .toList();
  }
}
