package mfs.mw.adapter.telco.partner.template;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.telco.TelcoFixtures.newTxnId;
import static mfs.mw.adapter.telco.TelcoFixtures.pay;
import static mfs.mw.adapter.telco.TelcoFixtures.statusQuery;
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
import mfs.mw.adapter.telco.mapper.PartnerCallMapper;
import mfs.mw.adapter.telco.partner.PartnerCall;
import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.telco.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Characterisation of the OCS partner (ticket 16 / 18 behaviour) after the move behind the
 * adapter framework: the OCS seed config of application-dev.yml, instantiated from the {@code
 * rest-json-request-reply} template, against the mw-simulators OCS wire format stubbed with
 * WireMock. Every way a call can end maps to one {@link PartnerResponse}, and the non-idempotent
 * top-up is never sent twice.
 */
class OcsTemplateTest {

  static final String REF = "UPAY01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";

  final PartnerCallMapper mapper = new PartnerCallMapper();
  WireMockServer ocs;
  PartnerFramework framework;

  @BeforeEach
  void startOcs() {
    ocs = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    ocs.start();
  }

  @AfterEach
  void stop() {
    if (framework != null) {
      framework.close();
    }
    ocs.stop();
  }

  PartnerConfig ocsConfig(String baseUrl, Duration limit) {
    return PartnerFramework.ocsSeed()
        .withBaseUrl(URI.create(baseUrl))
        .withTimeoutMs(limit.toMillis());
  }

  PartnerGateway gateway(Duration limit, CircuitBreakerConfig cb, BulkheadConfig bulkhead, String baseUrl) {
    framework =
        new PartnerFramework(
            "ocs", Map.of("ocs", ocsConfig(baseUrl, limit)), cb, bulkhead, ref -> "unused",
            Clock.systemUTC());
    return framework.gateway;
  }

  PartnerGateway gateway(Duration limit) {
    return gateway(
        limit, CircuitBreakerConfig.ofDefaults(), BulkheadConfig.ofDefaults(), "http://localhost:" + ocs.port());
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

  /** A top-up for gp: no gp config of its own, so the default (OCS) instance serves it. */
  PartnerCall topup() {
    return mapper.toCall(pay(newTxnId(), "01711000001", 2000), REF);
  }

  PartnerCall inquiry() {
    return mapper.toCall(statusQuery(newTxnId()), REF);
  }

  @Test
  void sendsTheTopupAsTheSimulatorExpectsItAndReturnsTheOcsReference() {
    stubPost(
        200,
        "{\"transactionId\":\"" + REF + "\",\"status\":\"SUCCESS\",\"code\":\"0000\",\"message\":\"ok\",\"ocsRef\":\"OCS-778899\"}");

    PartnerResponse result = gateway(Duration.ofSeconds(5)).call(topup());

    assertThat(result).isEqualTo(new PartnerResponse.Success("OCS-778899", "0000"));
    ocs.verify(
        1,
        postRequestedFor(urlEqualTo("/ocs/v1/topup"))
            .withHeader("X-Correlation-Id", equalTo("corr-0002"))
            .withRequestBody(
                equalToJson("{\"transactionId\":\"" + REF + "\",\"msisdn\":\"01711000001\",\"amount\":\"20.00\"}")));
  }

  @Test
  void theAmountKeepsItsFractionalPartOnTheWire() {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"ocsRef\":\"R\"}");

    gateway(Duration.ofSeconds(5)).call(mapper.toCall(pay(newTxnId(), "01711000001", 2013), REF));

    ocs.verify(postRequestedFor(urlEqualTo("/ocs/v1/topup")).withRequestBody(equalToJson(
        "{\"transactionId\":\"" + REF + "\",\"msisdn\":\"01711000001\",\"amount\":\"20.13\"}")));
  }

  @Test
  void aBusinessFailureIsRejectedWithTheOcsCodeAndItsMappedSuffix() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"Subscriber barred\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Rejected("2001", "Subscriber barred", "TOPUP_REJECTED", false));
  }

  @Test
  void anUnlistedCodeIsUnmappedAndNotRetryable() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"3003\",\"message\":\"x\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Rejected("3003", "x", "UNMAPPED", false));
  }

  @Test
  void anAmbiguousCodeIsNotADefiniteFailure() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"9876\",\"message\":\"Unknown code\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Ambiguous("9876", "Unknown code"));
  }

  @Test
  void anOtherClientErrorIsRejectedWithTheHttpStatusAsCode() {
    stubPost(400, "{\"code\":\"MW-VAL-001\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isInstanceOfSatisfying(
            PartnerResponse.Rejected.class, r -> assertThat(r.code()).isEqualTo("HTTP_400"));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 408, 409, 429, 302})
  void anyStatusOtherThan200400422500IsAnUnknownOutcomeNotARejection(int status) {
    stubPost(status, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"x\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));
  }

  @Test
  void aServerErrorAfterSendIsAnUnknownOutcome() {
    stubPost(500, "{\"status\":\"ERROR\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.HTTP_5XX));
  }

  @Test
  void aMalformedSuccessBodyIsAnUnknownOutcome() {
    stubPost(200, "<<not json>>");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
  }

  @Test
  void aSuccessReplyWithoutOcsRefIsMalformed() {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
  }

  @Test
  void aRejectionWithoutACodeIsMalformed() {
    stubPost(422, "{\"status\":\"FAILED\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
  }

  @Test
  void aReplyLaterThanTheTimeLimiterIsAnUnknownOutcome() {
    ocs.stubFor(
        post(urlEqualTo("/ocs/v1/topup"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(2500).withBody("{}")));

    assertThat(gateway(Duration.ofMillis(500)).call(topup()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.TIMEOUT));
  }

  @Test
  void aConnectionResetAfterSendIsAnUnknownOutcome() {
    ocs.stubFor(
        post(urlEqualTo("/ocs/v1/topup")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.CONNECTION_RESET));
  }

  @Test
  void aRefusedConnectionMeansNothingWasSent() {
    int closedPort = ocs.port();
    ocs.stop();
    PartnerGateway gateway =
        gateway(
            Duration.ofSeconds(5), CircuitBreakerConfig.ofDefaults(), BulkheadConfig.ofDefaults(),
            "http://localhost:" + closedPort);

    assertThat(gateway.call(topup())).isEqualTo(new PartnerResponse.NotSent(NotSentReason.CONNECT_FAILED));
  }

  @Test
  void aFailedTopupIsNeverRetried() {
    stubPost(500, "{}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup())).isInstanceOf(PartnerResponse.Unknown.class);

    ocs.verify(1, postRequestedFor(urlEqualTo("/ocs/v1/topup")));
  }

  @Test
  void theCircuitOpensAtTheConfiguredFailureRateAndThenFailsFastWithoutCallingOcs() {
    stubPost(500, "{}");
    PartnerGateway gateway =
        gateway(Duration.ofSeconds(5), fourCallWindow(), BulkheadConfig.ofDefaults(), "http://localhost:" + ocs.port());

    for (int i = 0; i < 4; i++) {
      assertThat(gateway.call(topup())).isInstanceOf(PartnerResponse.Unknown.class);
    }
    assertThat(gateway.call(topup())).isEqualTo(new PartnerResponse.NotSent(NotSentReason.CIRCUIT_OPEN));

    ocs.verify(4, postRequestedFor(urlEqualTo("/ocs/v1/topup")));
  }

  @Test
  void businessRejectionsDoNotCountTowardsOpeningTheCircuit() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"barred\"}");
    PartnerGateway gateway =
        gateway(Duration.ofSeconds(5), fourCallWindow(), BulkheadConfig.ofDefaults(), "http://localhost:" + ocs.port());

    for (int i = 0; i < 6; i++) {
      assertThat(gateway.call(topup())).isInstanceOf(PartnerResponse.Rejected.class);
    }
  }

  @Test
  void aFullBulkheadFailsFastWithoutCallingOcs() throws InterruptedException {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"ocsRef\":\"R1\"}", 1500);
    PartnerGateway gateway =
        gateway(
            Duration.ofSeconds(5), CircuitBreakerConfig.ofDefaults(),
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build(),
            "http://localhost:" + ocs.port());
    CompletableFuture<PartnerResponse> first =
        CompletableFuture.supplyAsync(() -> gateway.call(topup()), framework.executor);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (ocs.getAllServeEvents().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }

    assertThat(gateway.call(topup())).isEqualTo(new PartnerResponse.NotSent(NotSentReason.BULKHEAD_FULL));
    assertThat(first.join()).isInstanceOf(PartnerResponse.Success.class);
  }

  // --- status inquiry (ticket 18, spec 7.4) ------------------------------------------------------

  @Test
  void aStatusQueryAsksByTheStableReferenceAndReportsTheTrueOutcome() {
    stubGet(200, "{\"transactionId\":\"" + REF + "\",\"status\":\"SUCCESS\",\"code\":\"0000\",\"ocsRef\":\"OCS-55\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(inquiry()))
        .isEqualTo(new PartnerResponse.Success("OCS-55", "0000"));
    ocs.verify(1, getRequestedFor(urlEqualTo("/ocs/v1/topup/" + REF)));
  }

  @Test
  void aStatusQueryForAFailedTopupIsRejectedAndAnAmbiguousOneIsNot() {
    stubGet(200, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"barred\"}");
    PartnerGateway gateway = gateway(Duration.ofSeconds(5));
    assertThat(gateway.call(inquiry()))
        .isEqualTo(new PartnerResponse.Rejected("2001", "barred", "TOPUP_REJECTED", false));

    stubGet(200, "{\"status\":\"FAILED\",\"code\":\"9876\",\"message\":\"?\"}");
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.Ambiguous("9876", "?"));
  }

  @Test
  void notFoundOnlyForA404CarryingTheOcsNotFoundBody() {
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

    stubGet(409, "{\"status\":\"FAILED\",\"code\":\"2001\"}");
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));

    stubGet(200, "{\"status\":\"PROCESSING\"}");
    assertThat(gateway.call(inquiry())).isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
  }

  @Test
  void aNotFoundAnswerToAPayIsNeverBelieved() {
    stubPost(404, "{\"status\":\"NOT_FOUND\",\"code\":\"4040\"}");

    assertThat(gateway(Duration.ofSeconds(5)).call(topup()))
        .isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));
  }

  private void stubGet(int status, String body) {
    ocs.stubFor(
        get(urlEqualTo("/ocs/v1/topup/" + REF))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
  }

  private void stubPost(int status, String body) {
    stubPost(status, body, 0);
  }

  private void stubPost(int status, String body, int delayMs) {
    ocs.stubFor(
        post(urlEqualTo("/ocs/v1/topup"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withFixedDelay(delayMs)
                    .withBody(body)));
  }
}
