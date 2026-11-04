package mfs.mw.adapter.telco.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mfs.mw.adapter.telco.config.OcsProperties;
import mfs.mw.adapter.telco.dto.OcsResult;
import mfs.mw.adapter.telco.dto.OcsTopupRequest;
import mfs.mw.adapter.telco.exception.OcsNotSentException;
import mfs.mw.adapter.telco.exception.OcsUnknownOutcomeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OCS wire format of mw-simulators (services/mw-simulators/CLAUDE.md), stubbed with WireMock:
 * every way a call can end maps to a result or to exactly one of the two failure exceptions, and
 * the non-idempotent top-up is never sent twice by the client.
 */
class OcsClientTest {

  static final OcsTopupRequest TOPUP =
      new OcsTopupRequest("UPAY01933B8E7C1A7B2E9F3D2A4C5E6F7A8C", "01711000001", "20.00");

  WireMockServer ocs;
  ExecutorService executor;

  @BeforeEach
  void startOcs() {
    ocs = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    ocs.start();
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void stopOcs() {
    ocs.stop();
    executor.shutdownNow();
  }

  OcsProperties props(String baseUrl, Duration readTimeout) {
    return new OcsProperties(
        URI.create(baseUrl), Duration.ofSeconds(1), readTimeout, Set.of("9876"), Map.of());
  }

  OcsClient client(
      Duration limit, CircuitBreakerConfig cb, BulkheadConfig bulkhead, String baseUrl) {
    return new OcsClient(
        props(baseUrl, limit.plusSeconds(3)),
        JsonMapper.builder().build(),
        CircuitBreakerRegistry.of(cb),
        BulkheadRegistry.of(bulkhead),
        TimeLimiterRegistry.of(
            TimeLimiterConfig.custom().timeoutDuration(limit).cancelRunningFuture(true).build()),
        executor);
  }

  OcsClient client(Duration limit) {
    return client(
        limit,
        CircuitBreakerConfig.ofDefaults(),
        BulkheadConfig.ofDefaults(),
        "http://localhost:" + ocs.port());
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

  @Test
  void sendsTheTopupAsTheSimulatorExpectsItAndReturnsTheOcsReference() {
    stubPost(
        200,
        """
        {"transactionId":"UPAY01933B8E7C1A7B2E9F3D2A4C5E6F7A8C","status":"SUCCESS",\
        "code":"0000","message":"ok","ocsRef":"OCS-778899"}""");

    OcsResult result = client(Duration.ofSeconds(5)).topup(TOPUP);

    assertThat(result).isEqualTo(new OcsResult.Accepted("OCS-778899", "0000"));
    ocs.verify(
        1,
        postRequestedFor(urlEqualTo("/ocs/v1/topup"))
            .withRequestBody(
                equalToJson(
                    """
                    {"transactionId":"UPAY01933B8E7C1A7B2E9F3D2A4C5E6F7A8C",\
                    "msisdn":"01711000001","amount":"20.00"}""")));
  }

  @Test
  void aBusinessFailureIsRejectedWithTheOcsCode() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"Subscriber barred\"}");

    assertThat(client(Duration.ofSeconds(5)).topup(TOPUP))
        .isEqualTo(new OcsResult.Rejected("2001", "Subscriber barred"));
  }

  @Test
  void anAmbiguousCodeIsNotADefiniteFailure() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"9876\",\"message\":\"Unknown code\"}");

    assertThat(client(Duration.ofSeconds(5)).topup(TOPUP))
        .isEqualTo(new OcsResult.Ambiguous("9876", "Unknown code"));
  }

  @Test
  void anOtherClientErrorIsRejectedWithTheHttpStatusAsCode() {
    stubPost(400, "{\"code\":\"MW-VAL-001\"}");

    assertThat(client(Duration.ofSeconds(5)).topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsResult.Rejected.class, r -> assertThat(r.code()).isEqualTo("HTTP_400"));
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 408, 409, 429, 302})
  void anyStatusOtherThan200400422500IsAnUnknownOutcomeNotARejection(int status) {
    stubPost(status, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"x\"}");
    OcsClient client = client(Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsUnknownOutcomeException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(OcsUnknownOutcomeException.Reason.UNEXPECTED_STATUS));
  }

  @Test
  void aServerErrorAfterSendIsAnUnknownOutcome() {
    stubPost(500, "{\"status\":\"ERROR\"}");
    OcsClient client = client(Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsUnknownOutcomeException.class,
            e -> assertThat(e.reason()).isEqualTo(OcsUnknownOutcomeException.Reason.HTTP_5XX));
  }

  @Test
  void aMalformedSuccessBodyIsAnUnknownOutcome() {
    stubPost(200, "<<not json>>");
    OcsClient client = client(Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsUnknownOutcomeException.class,
            e ->
                assertThat(e.reason()).isEqualTo(OcsUnknownOutcomeException.Reason.MALFORMED_REPLY));
  }

  @Test
  void aSuccessReplyWithoutOcsRefIsMalformed() {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\"}");
    OcsClient client = client(Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsUnknownOutcomeException.class,
            e ->
                assertThat(e.reason()).isEqualTo(OcsUnknownOutcomeException.Reason.MALFORMED_REPLY));
  }

  @Test
  void aReplyLaterThanTheTimeLimiterIsAnUnknownOutcome() {
    ocs.stubFor(
        post(urlEqualTo("/ocs/v1/topup"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(2500).withBody("{}")));
    OcsClient client = client(Duration.ofMillis(500));

    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsUnknownOutcomeException.class,
            e -> assertThat(e.reason()).isEqualTo(OcsUnknownOutcomeException.Reason.TIMEOUT));
  }

  @Test
  void aConnectionResetAfterSendIsAnUnknownOutcome() {
    ocs.stubFor(
        post(urlEqualTo("/ocs/v1/topup"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    OcsClient client = client(Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsUnknownOutcomeException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(OcsUnknownOutcomeException.Reason.CONNECTION_RESET));
  }

  @Test
  void aRefusedConnectionMeansNothingWasSent() {
    int closedPort = ocs.port();
    ocs.stop();
    OcsClient client =
        client(
            Duration.ofSeconds(5),
            CircuitBreakerConfig.ofDefaults(),
            BulkheadConfig.ofDefaults(),
            "http://localhost:" + closedPort);

    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsNotSentException.class,
            e -> assertThat(e.reason()).isEqualTo(OcsNotSentException.Reason.CONNECT_FAILED));
  }

  @Test
  void aFailedTopupIsNeverRetriedByTheClient() {
    stubPost(500, "{}");
    OcsClient client = client(Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.topup(TOPUP)).isInstanceOf(OcsUnknownOutcomeException.class);

    ocs.verify(1, postRequestedFor(urlEqualTo("/ocs/v1/topup")));
  }

  @Test
  void theCircuitOpensAtTheConfiguredFailureRateAndThenFailsFastWithoutCallingOcs() {
    stubPost(500, "{}");
    OcsClient client =
        client(
            Duration.ofSeconds(5),
            fourCallWindow(),
            BulkheadConfig.ofDefaults(),
            "http://localhost:" + ocs.port());

    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> client.topup(TOPUP)).isInstanceOf(OcsUnknownOutcomeException.class);
    }
    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsNotSentException.class,
            e -> assertThat(e.reason()).isEqualTo(OcsNotSentException.Reason.CIRCUIT_OPEN));

    ocs.verify(4, postRequestedFor(urlEqualTo("/ocs/v1/topup")));
  }

  @Test
  void businessRejectionsDoNotCountTowardsOpeningTheCircuit() {
    stubPost(422, "{\"status\":\"FAILED\",\"code\":\"2001\",\"message\":\"barred\"}");
    OcsClient client =
        client(
            Duration.ofSeconds(5),
            fourCallWindow(),
            BulkheadConfig.ofDefaults(),
            "http://localhost:" + ocs.port());

    for (int i = 0; i < 6; i++) {
      assertThat(client.topup(TOPUP)).isInstanceOf(OcsResult.Rejected.class);
    }
  }

  @Test
  void aFullBulkheadFailsFastWithoutCallingOcs() throws InterruptedException {
    stubPost(200, "{\"status\":\"SUCCESS\",\"code\":\"0000\",\"ocsRef\":\"R1\"}", 1500);
    OcsClient client =
        client(
            Duration.ofSeconds(5),
            CircuitBreakerConfig.ofDefaults(),
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build(),
            "http://localhost:" + ocs.port());
    CompletableFuture<OcsResult> first =
        CompletableFuture.supplyAsync(() -> client.topup(TOPUP), executor);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (ocs.getAllServeEvents().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }

    assertThatThrownBy(() -> client.topup(TOPUP))
        .isInstanceOfSatisfying(
            OcsNotSentException.class,
            e -> assertThat(e.reason()).isEqualTo(OcsNotSentException.Reason.BULKHEAD_FULL));
    assertThat(first.join()).isInstanceOf(OcsResult.Accepted.class);
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
