package mfs.mw.adapter.bank.partner.template;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.bank.BankFixtures.newTxnId;
import static mfs.mw.adapter.bank.BankFixtures.pay;
import static mfs.mw.adapter.bank.BankFixtures.statusQuery;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import mfs.mw.adapter.bank.mapper.PartnerCallMapper;
import mfs.mw.adapter.bank.partner.InboundCall;
import mfs.mw.adapter.bank.partner.InboundResult;
import mfs.mw.adapter.bank.partner.PartnerCall;
import mfs.mw.adapter.bank.partner.PartnerResponse;
import mfs.mw.adapter.bank.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.bank.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.bank.partner.config.PartnerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The C2W bank (ticket 23): the c2wbank seed config of application.yml, instantiated from the
 * {@code rest-json-async-callback} template, against the mw-simulators bank wire format stubbed
 * with WireMock. Every way a call can end maps to one {@link PartnerResponse}, the non-idempotent
 * debit is never sent twice, and signed callbacks map to our reference.
 */
class BankTemplateTest {

  static final String REF = "UPAY01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";

  final PartnerCallMapper mapper = new PartnerCallMapper();
  WireMockServer agg;
  PartnerFramework framework;

  @BeforeEach
  void startBank() {
    agg = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    agg.start();
  }

  @AfterEach
  void stop() {
    if (framework != null) {
      framework.close();
    }
    agg.stop();
  }

  PartnerConfig aggConfig(String baseUrl, Duration limit) {
    return PartnerFramework.c2wbankSeed()
        .withBaseUrl(URI.create(baseUrl))
        .withTimeoutMs(limit.toMillis());
  }

  PartnerGateway gateway(Duration limit, CircuitBreakerConfig cb, BulkheadConfig bulkhead, String baseUrl) {
    framework =
        new PartnerFramework(
            "c2wbank", Map.of("c2wbank", aggConfig(baseUrl, limit)), cb, bulkhead, ref -> "unused",
            Clock.systemUTC());
    return framework.gateway;
  }

  PartnerGateway gateway(Duration limit) {
    return gateway(
        limit, CircuitBreakerConfig.ofDefaults(), BulkheadConfig.ofDefaults(), "http://localhost:" + agg.port());
  }

  CircuitBreakerConfig fourCallWindow() {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(4)
        .minimumNumberOfCalls(4)
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofMinutes(5))
        .build();
  }

  /** A C2W debit of a bank account through c2wbank. */
  PartnerCall payment() {
    return mapper.toCall(pay(newTxnId(), "1234567890123", 2000), REF);
  }

  PartnerCall inquiry() {
    return mapper.toCall(statusQuery(newTxnId()), REF);
  }

  @Test
  void sendsTheDebitAsTheSimulatorExpectsItAndReturnsTheBankReference() {
    stubPost(
        200,
        "{\"transactionId\":\"" + REF + "\",\"status\":\"SUCCESS\",\"code\":\"0000\",\"message\":\"ok\",\"debitRef\":\"C2W-778899\"}");

    PartnerResponse result = gateway(Duration.ofSeconds(5)).call(payment());

    assertThat(result).isEqualTo(new PartnerResponse.Success("C2W-778899", "0000"));
    agg.verify(
        1,
        postRequestedFor(urlEqualTo("/c2wbank/v1/debits"))
            .withHeader("X-Correlation-Id", equalTo("corr-0002"))
            .withRequestBody(
                equalToJson("{\"transactionId\":\"" + REF + "\",\"accountNumber\":\"1234567890123\",\"amount\":\"20.00\"}")));
  }

  @Test
  void theAmountKeepsItsFractionalPartOnTheWire() {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"debitRef\":\"R\"}");

    gateway(Duration.ofSeconds(5)).call(mapper.toCall(pay(newTxnId(), "1234567890123", 2013), REF));

    agg.verify(postRequestedFor(urlEqualTo("/c2wbank/v1/debits")).withRequestBody(equalToJson(
        "{\"transactionId\":\"" + REF + "\",\"accountNumber\":\"1234567890123\",\"amount\":\"20.13\"}")));
  }

  @Test
  void aBusinessFailureIsRejectedWithTheBankCodeAndItsMappedSuffix() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"4002\",\"message\":\"Subscriber barred\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Rejected("4002", "Subscriber barred", "DEBIT_REFUSED", false));
  }

  @Test
  void anUnlistedCodeIsUnmappedAndNotRetryable() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"4999\",\"message\":\"x\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Rejected("4999", "x", "UNMAPPED", false));
  }

  @Test
  void anAmbiguousCodeIsNotADefiniteFailure() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"9876\",\"message\":\"Unknown code\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Ambiguous("9876", "Unknown code"));
  }

  @Test
  void anOtherClientErrorIsRejectedWithTheHttpStatusAsCode() {
    stubPost(400, "{\"code\":\"MW-VAL-001\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isInstanceOfSatisfying(
            PartnerResponse.Rejected.class, r -> assertThat(r.code()).isEqualTo("HTTP_400"));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 408, 409, 429, 302})
  void anyStatusOtherThan200400422500IsAnUnknownOutcomeNotARejection(int status) {
    stubPost(status, "{\"status\":\"FAILED\",\"code\":\"4002\",\"message\":\"x\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));
  }

  @Test
  void aServerErrorAfterSendIsAnUnknownOutcome() {
    stubPost(500, "{\"status\":\"ERROR\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.HTTP_5XX));
  }

  @Test
  void aMalformedSuccessBodyIsAnUnknownOutcome() {
    stubPost(200, "<<not json>>");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
  }

  @Test
  void aSuccessReplyWithoutBankRefIsMalformed() {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
  }

  @Test
  void aRejectionWithoutACodeIsMalformed() {
    stubPost(422, "{\"status\":\"FAILED\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
  }

  @Test
  void aReplyLaterThanTheTimeLimiterIsAnUnknownOutcome() {
    agg.stubFor(
        post(urlEqualTo("/c2wbank/v1/debits"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(2500).withBody("{}")));

    assertThat(gateway(Duration.ofMillis(500)).call(payment()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.TIMEOUT));
  }

  @Test
  void aConnectionResetAfterSendIsAnUnknownOutcome() {
    agg.stubFor(
        post(urlEqualTo("/c2wbank/v1/debits")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.CONNECTION_RESET));
  }

  @Test
  void aRefusedConnectionMeansNothingWasSent() {
    int closedPort = agg.port();
    agg.stop();
    PartnerGateway gateway =
        gateway(
            Duration.ofSeconds(5), CircuitBreakerConfig.ofDefaults(), BulkheadConfig.ofDefaults(),
            "http://localhost:" + closedPort);

    assertThat(gateway.call(payment())).isEqualTo(new PartnerResponse.NotSent(NotSentReason.CONNECT_FAILED));
  }

  @Test
  void aFailedDebitIsNeverRetried() {
    stubPost(500, "{}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment())).isInstanceOf(PartnerResponse.Unknown.class);

    agg.verify(1, postRequestedFor(urlEqualTo("/c2wbank/v1/debits")));
  }

  @Test
  void theCircuitOpensAtTheConfiguredFailureRateAndThenFailsFastWithoutCallingTheBank() {
    stubPost(500, "{}");
    PartnerGateway gateway =
        gateway(Duration.ofSeconds(5), fourCallWindow(), BulkheadConfig.ofDefaults(), "http://localhost:" + agg.port());

    for (int i = 0; i < 4; i++) {
      assertThat(gateway.call(payment())).isInstanceOf(PartnerResponse.Unknown.class);
    }
    assertThat(gateway.call(payment())).isEqualTo(new PartnerResponse.NotSent(NotSentReason.CIRCUIT_OPEN));

    agg.verify(4, postRequestedFor(urlEqualTo("/c2wbank/v1/debits")));
  }

  @Test
  void businessRejectionsDoNotCountTowardsOpeningTheCircuit() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"4002\",\"message\":\"barred\"}");
    PartnerGateway gateway =
        gateway(Duration.ofSeconds(5), fourCallWindow(), BulkheadConfig.ofDefaults(), "http://localhost:" + agg.port());

    for (int i = 0; i < 6; i++) {
      assertThat(gateway.call(payment())).isInstanceOf(PartnerResponse.Rejected.class);
    }
  }

  @Test
  void aFullBulkheadFailsFastWithoutCallingTheBank() throws InterruptedException {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"debitRef\":\"R1\"}", 1500);
    PartnerGateway gateway =
        gateway(
            Duration.ofSeconds(5), CircuitBreakerConfig.ofDefaults(),
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build(),
            "http://localhost:" + agg.port());
    CompletableFuture<PartnerResponse> first =
        CompletableFuture.supplyAsync(() -> gateway.call(payment()), framework.executor);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (agg.getAllServeEvents().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }

    assertThat(gateway.call(payment())).isEqualTo(new PartnerResponse.NotSent(NotSentReason.BULKHEAD_FULL));
    assertThat(first.join()).isInstanceOf(PartnerResponse.Success.class);
  }

  // --- status inquiry (ticket 18, spec 7.4) ------------------------------------------------------

  @Test
  void aStatusQueryAsksByTheStableReferenceAndReportsTheTrueOutcome() {
    stubGet(200, "{\"transactionId\":\"" + REF + "\",\"status\":\"SUCCESS\",\"code\":\"0000\",\"debitRef\":\"C2W-55\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(inquiry()))
        .isEqualTo(new PartnerResponse.Success("C2W-55", "0000"));
    agg.verify(1, getRequestedFor(urlEqualTo("/c2wbank/v1/debits/" + REF)));
  }

  @Test
  void aStatusQueryForAFailedPaymentIsRejectedAndAnAmbiguousOneIsNot() {
    stubGet(200, "{\"status\":\"FAILED\",\"code\":\"4002\",\"message\":\"barred\"}");
    PartnerGateway gateway = gateway(Duration.ofSeconds(5));
    assertThat(gateway.call(inquiry()))
        .isEqualTo(new PartnerResponse.Rejected("4002", "barred", "DEBIT_REFUSED", false));

    stubGet(200, "{\"status\":\"FAILED\",\"code\":\"9876\",\"message\":\"?\"}");
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.Ambiguous("9876", "?"));
  }

  @Test
  void notFoundOnlyForA404CarryingTheBankNotFoundBody() {
    stubGet(404, "{\"status\":\"NOT_FOUND\",\"code\":\"4040\",\"message\":\"Unknown transaction\"}");
    PartnerGateway gateway = gateway(Duration.ofSeconds(5));
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.NotFound("4040", "Unknown transaction"));

    stubGet(404, "<html>no route</html>");
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));
  }

  @Test
  void aStatusQueryWithoutADefiniteAnswerIsUnknown() {
    stubGet(503, "{\"status\":\"UNAVAILABLE\"}");
    PartnerGateway gateway = gateway(Duration.ofSeconds(5));
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.Unknown(UnknownReason.HTTP_5XX));

    stubGet(409, "{\"status\":\"FAILED\",\"code\":\"4002\"}");
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));

    stubGet(200, "{\"status\":\"PROCESSING\"}");
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
  }

  @Test
  void aNotFoundAnswerToAPayIsNeverBelieved() {
    stubPost(404, "{\"status\":\"NOT_FOUND\",\"code\":\"4040\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));
  }

  private void stubGet(int status, String body) {
    agg.stubFor(
        get(urlEqualTo("/c2wbank/v1/debits/" + REF))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
  }

  // --- accepted debits and callbacks (ticket 23) ------------------------------------

  static final String CALLBACK_PATH = "/partner/c2wbank/callback/debits";

  /** A c2wbank callback signed with {@code secret} as mw-simulators signs it. */
  static InboundCall signedCallback(String secret, String body, long epochSeconds) {
    String ts = Long.toString(epochSeconds);
    String sig =
        mfs.mw.adapter.bank.partner.auth.HmacSha256AuthScheme.sign(
            secret, "POST", CALLBACK_PATH, ts, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new InboundCall(
        "c2wbank", "POST", CALLBACK_PATH,
        Map.of("X-Signature", sig, "X-Timestamp", ts, "Content-Type", "application/json"),
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static InboundCall signedCallback(String body) {
    return signedCallback("unused", body, java.time.Instant.now().getEpochSecond());
  }

  @Test
  void anAcceptedDebitIsPendingAndTheOutcomeFollowsByCallback() {
    stubPost(
        202,
        "{\"transactionId\":\"" + REF + "\",\"status\":\"PENDING\",\"code\":\"100\",\"message\":\"Accepted\",\"debitRef\":\"C2W-5\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(payment())).isEqualTo(new PartnerResponse.Pending("C2W-5", "100"));
  }

  @Test
  void outboundCallsAreSignedWithTheBankSecret() {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"000\",\"debitRef\":\"R\"}");

    gateway(Duration.ofSeconds(5)).call(payment());

    agg.verify(
        postRequestedFor(urlEqualTo("/c2wbank/v1/debits"))
            .withHeader("X-Signature", com.github.tomakehurst.wiremock.client.WireMock.matching(".+"))
            .withHeader("X-Timestamp", com.github.tomakehurst.wiremock.client.WireMock.matching("[0-9]+")));
  }

  @Test
  void aSignedSuccessCallbackMapsToTheBankReferenceAndOurs() {
    String body =
        "{\"transactionId\":\"" + REF + "\",\"status\":\"SUCCESS\",\"code\":\"000\",\"message\":\"Account debited\",\"debitRef\":\"C2W-5\"}";

    InboundResult r = gateway(Duration.ofSeconds(5)).inbound(signedCallback(body));

    assertThat(r).isEqualTo(new InboundResult("C2W-5", REF, "SUCCESS", "000", "Account debited", Map.of()));
  }

  @Test
  void aSignedFailureOrPendingCallbackKeepsItsOutcomeAndCode() {
    PartnerGateway g = gateway(Duration.ofSeconds(5));
    String failed = "{\"transactionId\":\"" + REF + "\",\"status\":\"FAILED\",\"code\":\"4002\",\"message\":\"Refused\"}";
    String pending = "{\"transactionId\":\"" + REF + "\",\"status\":\"PENDING\",\"code\":\"100\",\"debitRef\":\"C2W-5\"}";

    assertThat(g.inbound(signedCallback(failed)))
        .isEqualTo(new InboundResult(null, REF, "FAILED", "4002", "Refused", Map.of()));
    assertThat(g.inbound(signedCallback(pending)).outcome()).isEqualTo("PENDING");
  }

  @Test
  void aCallbackWithABadSignatureOrAStaleTimestampIsNotAuthentic() {
    PartnerGateway g = gateway(Duration.ofSeconds(5));
    String body = "{\"transactionId\":\"" + REF + "\",\"status\":\"SUCCESS\",\"code\":\"000\",\"debitRef\":\"C2W-5\"}";
    long now = java.time.Instant.now().getEpochSecond();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> g.inbound(signedCallback("other-secret", body, now)))
        .isInstanceOf(mfs.mw.adapter.bank.partner.PartnerAuthenticationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> g.inbound(signedCallback("unused", body, now - 3600)))
        .isInstanceOf(mfs.mw.adapter.bank.partner.PartnerAuthenticationException.class);
  }

  @Test
  void aCallbackWithoutAKnownStatusIsRefusedAsUnreadable() {
    String body = "{\"transactionId\":\"" + REF + "\",\"status\":\"MAYBE\"}";

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway(Duration.ofSeconds(5)).inbound(signedCallback(body)))
        .isInstanceOf(mfs.mw.adapter.bank.partner.mapping.JsonataMapping.MappingException.class);
  }

  private void stubPost(int status, String body) {
    stubPost(status, body, 0);
  }

  private void stubPost(int status, String body, int delayMs) {
    agg.stubFor(
        post(urlEqualTo("/c2wbank/v1/debits"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(delayMs)
                    .withBody(body)));
  }
}
