package mfs.mw.adapter.telco.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.telco.TelcoFixtures.newTxnId;
import static mfs.mw.adapter.telco.TelcoFixtures.expiredPay;
import static mfs.mw.adapter.telco.TelcoFixtures.pay;
import static mfs.mw.adapter.telco.TelcoFixtures.statusQuery;
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
import mfs.mw.adapter.telco.TestInfrastructure;
import mfs.mw.adapter.telco.contracts.ContractSchemas;
import mfs.mw.adapter.telco.dto.PartnerReply;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import mfs.mw.adapter.telco.messaging.EventMetadata;
import mfs.mw.adapter.telco.messaging.EventPublisher;
import mfs.mw.adapter.telco.messaging.KafkaTestConsumer;
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
 * Route tests (ticket 16): a canonical top-up request on {@code mw.partner.request.telco.v1}
 * reaches the OCS (WireMock, wire format of mw-simulators) and every outcome comes back on {@code
 * mw.partner.reply.v1} with the right status and code, valid against the contract. Real Kafka and
 * PostgreSQL (Testcontainers). Time limiter 1.5 s and a 4-call circuit window keep it quick.
 */
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.telco.request-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "mw.telco.ocs.read-timeout=4s",
      "resilience4j.timelimiter.instances.ocs.timeout-duration=1500ms",
      "resilience4j.circuitbreaker.instances.ocs.sliding-window-type=COUNT_BASED",
      "resilience4j.circuitbreaker.instances.ocs.sliding-window-size=4",
      "resilience4j.circuitbreaker.instances.ocs.minimum-number-of-calls=4",
      "resilience4j.circuitbreaker.instances.ocs.failure-rate-threshold=50"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class TelcoRequestRouteIT {

  static final String REQUEST_TOPIC = "mw.partner.request.telco.v1";
  static final String REPLY_TOPIC = "mw.partner.reply.v1";
  static final String REPLY_SCHEMA = "events/mw-orchestrator/mw.partner.reply.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();

  static final WireMockServer OCS =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    OCS.start();
  }

  @DynamicPropertySource
  static void ocsUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.telco.ocs.base-url", () -> "http://localhost:" + OCS.port());
  }

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JdbcTemplate jdbc;
  private final CircuitBreakerRegistry breakers;

  @Autowired
  TelcoRequestRouteIT(
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
    OCS.resetAll();
    breakers.circuitBreaker("ocs").reset();
  }

  @Test
  void successIsRepliedWithTheOcsReferenceAndTheStablePartnerReference() throws Exception {
    stubOcs(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"message\":\"ok\",\"ocsRef\":\"OCS-778899\"}");
    PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.requestId()).isEqualTo(request.requestId());
    assertThat(reply.partnerId()).isEqualTo("gp");
    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("OCS-778899");
    assertThat(reply.payload().partnerCode()).isEqualTo("0000");
    String ref = "UPAY" + request.txnId().replace("-", "").toUpperCase();
    assertThat(reply.payload().attributes()).containsEntry("partnerTxnRef", ref);
    OCS.verify(
        1,
        postRequestedFor(urlEqualTo("/ocs/v1/topup"))
            .withRequestBody(
                equalToJson(
                    "{\"transactionId\":\"" + ref + "\",\"msisdn\":\"01711000001\",\"amount\":\"20.00\"}")));
  }

  @Test
  void aBusinessFailureIsFailedWithTheMappedPartnerCode() throws Exception {
    stubOcs(422, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"Subscriber barred\"}");
    PartnerRequest request = pay(newTxnId(), "01711000999", 2000);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-GP-TOPUP_REJECTED");
    assertThat(reply.payload().partnerCode()).isEqualTo("2001");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void aReplyLaterThanTheTimeLimiterIsUnknown() throws Exception {
    OCS.stubFor(
        post(urlEqualTo("/ocs/v1/topup")).willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));
    PartnerRequest request = pay(newTxnId(), "01711000113", 2013);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_TIMEOUT");
  }

  @Test
  void aServerErrorAfterSendIsUnknown() throws Exception {
    stubOcs(500, "{\"status\":\"ERROR\"}");
    PartnerRequest request = pay(newTxnId(), "01711000001", 2077);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_HTTP_5XX");
  }

  @Test
  void conflictAndTooManyRequestsAreUnknownNotFailed() throws Exception {
    for (int status : new int[] {409, 429}) {
      OCS.resetAll();
      stubOcs(status, "{\"status\":\"FAILED\",\"code\":\"2001\"}");
      PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

      send(request);
      PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

      assertThat(reply.payload().outcome()).as("HTTP %s", status).isEqualTo("UNKNOWN");
      assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_UNEXPECTED_STATUS");
      assertThat(reply.payload().retryable()).isFalse();
    }
  }

  @Test
  void aConnectionResetAfterSendIsUnknown() throws Exception {
    OCS.stubFor(
        post(urlEqualTo("/ocs/v1/topup")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_CONNECTION_RESET");
  }

  @Test
  void anAmbiguousOcsCodeIsUnknown() throws Exception {
    stubOcs(422, "{\"status\":\"FAILED\",\"code\":\"9876\",\"message\":\"???\"}");
    PartnerRequest request = pay(newTxnId(), "01711000001", 2099);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_AMBIGUOUS_CODE");
    assertThat(reply.payload().partnerCode()).isEqualTo("9876");
  }

  @Test
  void aMalformedOcsReplyIsUnknown() throws Exception {
    stubOcs(200, "<<garbage>>");
    PartnerRequest request = pay(newTxnId(), "01711000001", 2055);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_MALFORMED_REPLY");
  }

  @Test
  void aRequestThatCannotBeMappedIsFailedWithoutCallingTheOcs() throws Exception {
    PartnerRequest request = pay(newTxnId(), "01711000001", 0);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-VAL-INVALID_PARTNER_REQUEST");
    OCS.verify(0, postRequestedFor(urlEqualTo("/ocs/v1/topup")));
  }

  @Test
  void aDuplicateRequestForTheSameTxnIdReusesThePartnerReference() throws Exception {
    stubOcs(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"ocsRef\":\"OCS-1\"}");
    String txnId = newTxnId();

    send(pay(txnId, "01711000001", 2000));
    awaitReplies(txnId, 1);
    send(pay(txnId, "01711000001", 2000));
    List<PartnerReply> replies = awaitReplies(txnId, 2);

    List<LoggedRequest> calls = OCS.findAll(postRequestedFor(urlEqualTo("/ocs/v1/topup")));
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
    stubOcs(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"ocsRef\":\"OCS-1\"}");
    PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

    send(request);
    awaitReplies(request.txnId(), 1);
    send(request);
    Thread.sleep(3000);

    assertThat(replies(request.txnId(), 2, Duration.ofSeconds(2))).hasSize(1);
  }

  @Test
  void aFailedTopupIsNotRetried() throws Exception {
    stubOcs(500, "{}");
    PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

    send(request);
    awaitReplies(request.txnId(), 1);
    Thread.sleep(2000);

    OCS.verify(1, postRequestedFor(urlEqualTo("/ocs/v1/topup")));
    assertThat(
            KafkaTestConsumer.read(
                kafka.getBootstrapServers(), REQUEST_TOPIC + ".retry.1", 1, Duration.ofSeconds(1),
                r -> request.txnId().equals(r.key())))
        .isEmpty();
  }

  @Test
  void onceTheCircuitIsOpenRequestsAreFailedFastWithoutCallingTheOcs() throws Exception {
    stubOcs(500, "{}");
    for (int i = 0; i < 4; i++) {
      PartnerRequest request = pay(newTxnId(), "01711000001", 2000);
      send(request);
      assertThat(awaitReplies(request.txnId(), 1).getFirst().payload().outcome()).isEqualTo("UNKNOWN");
    }

    PartnerRequest next = pay(newTxnId(), "01711000001", 2000);
    send(next);
    PartnerReply reply = awaitReplies(next.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-SYS-PARTNER_CIRCUIT_OPEN");
    assertThat(reply.payload().retryable()).isTrue();
    OCS.verify(4, postRequestedFor(urlEqualTo("/ocs/v1/topup")));
  }

  // --- status inquiry (ticket 18, spec 7.4) ------------------------------------------------------

  private static String ref(String txnId) {
    return "UPAY" + txnId.replace("-", "").toUpperCase();
  }

  private void stubStatus(String txnId, int status, String body) {
    OCS.stubFor(
        get(urlEqualTo("/ocs/v1/topup/" + ref(txnId)))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void aStatusQueryAsksTheOcsWithTheStableReferenceAndReportsTheTrueOutcome() throws Exception {
    PartnerRequest request = statusQuery(newTxnId());
    stubStatus(request.txnId(), 200,
        "{\"transactionId\":\"" + ref(request.txnId()) + "\",\"status\":\"SUCCESS\",\"code\":\"0000\",\"ocsRef\":\"OCS-55\"}");

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.requestId()).isEqualTo(request.requestId());
    assertThat(reply.payload().operation()).isEqualTo("STATUS_QUERY");
    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("OCS-55");
    OCS.verify(1, getRequestedFor(urlEqualTo("/ocs/v1/topup/" + ref(request.txnId()))));
    OCS.verify(0, postRequestedFor(urlEqualTo("/ocs/v1/topup")));
  }

  @Test
  void aStatusQueryForATopupTheOcsNeverSawIsNotFound() throws Exception {
    PartnerRequest request = statusQuery(newTxnId());
    stubStatus(request.txnId(), 404, "{\"status\":\"NOT_FOUND\",\"code\":\"4040\",\"message\":\"Unknown transaction\"}");

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("NOT_FOUND");
    assertThat(reply.payload().partnerCode()).isEqualTo("4040");
  }

  @Test
  void aStatusQueryForAFailedTopupIsFailedWithTheMappedCode() throws Exception {
    PartnerRequest request = statusQuery(newTxnId());
    stubStatus(request.txnId(), 200, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"barred\"}");

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-GP-TOPUP_REJECTED");
  }

  @Test
  void aStatusQueryWithoutADefiniteAnswerIsUnknownNeverFailed() throws Exception {
    for (int status : new int[] {503, 404}) {
      OCS.resetAll();
      PartnerRequest request = statusQuery(newTxnId());
      // a 404 without the OCS' NOT_FOUND body (a proxy, a wrong path) is not an answer either
      stubStatus(request.txnId(), status, status == 404 ? "<html>no route</html>" : "{\"status\":\"UNAVAILABLE\"}");

      send(request);
      PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

      assertThat(reply.payload().outcome()).as("HTTP %s", status).isEqualTo("UNKNOWN");
    }
  }

  @Test
  void aPayPastItsDeadlineIsRefusedWithoutCallingTheOcs() throws Exception {
    PartnerRequest request = expiredPay(newTxnId(), "01711000001", 2000);

    send(request);
    PartnerReply reply = awaitReplies(request.txnId(), 1).getFirst();

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-SYS-PARTNER_REQUEST_EXPIRED");
    OCS.verify(0, anyRequestedFor(anyUrl()));
  }

  private void stubOcs(int status, String body) {
    OCS.stubFor(
        post(urlEqualTo("/ocs/v1/topup"))
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
                  .containsEntry("mw-source", "mw-adapter-telco");
              return JSON.readValue(r.value(), PartnerReply.class);
            })
        .toList();
  }
}
