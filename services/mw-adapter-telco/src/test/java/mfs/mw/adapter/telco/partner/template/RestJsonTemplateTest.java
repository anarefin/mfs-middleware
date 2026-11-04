package mfs.mw.adapter.telco.partner.template;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.telco.dto.ConfigChangedEvent;
import mfs.mw.adapter.telco.partner.InboundCall;
import mfs.mw.adapter.telco.partner.InboundResult;
import mfs.mw.adapter.telco.partner.PartnerAuthenticationException;
import mfs.mw.adapter.telco.partner.PartnerCall;
import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.telco.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.telco.partner.PartnerUnsupportedException;
import mfs.mw.adapter.telco.partner.auth.HmacSha256AuthScheme;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;
import mfs.mw.adapter.telco.util.SensitiveType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The adapter framework at its entry point ({@link PartnerGateway}): a partner instance created
 * purely from a config record (template + endpoint + JSONata mappings + error map + auth),
 * called against WireMock; config changes taking effect on the next call; the wrapper's masked
 * audit and §21.2 metrics; the async-callback template. The partner "acme" and its wire format
 * are made up for the test.
 */
class RestJsonTemplateTest {

  static final String SECRET = "fake-acme-key-not-real";

  WireMockServer partner;
  PartnerFramework framework;

  @BeforeEach
  void start() {
    partner = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    partner.start();
  }

  @AfterEach
  void stop() {
    if (framework != null) {
      framework.close();
    }
    partner.stop();
  }

  /** A made-up partner: amounts in minor units, api-key auth, its own codes. */
  PartnerConfig acme(String template, String payResponseMapping) {
    return new PartnerConfig(
        true,
        template,
        URI.create("http://localhost:" + partner.port() + "/"),
        1000L,
        5000L,
        null,
        Map.of("X-Channel", "upay"),
        new PartnerConfig.Auth("api-key", "api-key", Map.of("header", "X-Acme-Key")),
        Map.of(
            "PAY",
            new PartnerConfig.Operation(
                "POST", "/v2/payments", List.of(400, 409),
                "{\"ref\": reference, \"to\": creditParty.id, \"minor\": amount.valueMinor}",
                payResponseMapping)),
        Map.of("E17", new PartnerConfig.ErrorMapping("ACCOUNT_CLOSED", false)),
        Set.of("E99"),
        Map.of("to", SensitiveType.MSISDN),
        null);
  }

  static final String ACME_RESPONSE =
      """
      status = 200 and body.result = "OK" ? {"outcome": "SUCCESS", "partnerRef": body.id}
      : status = 202 ? {"outcome": "PENDING", "partnerRef": body.id}
      : body.result = "ERR" ? {"outcome": "FAILED", "code": body.error, "message": body.text}
      : {"outcome": "UNKNOWN", "reason": "UNEXPECTED_STATUS"}
      """;

  PartnerGateway framework(Map<String, PartnerConfig> instances, String defaultPartner) {
    framework =
        new PartnerFramework(
            defaultPartner, instances, CircuitBreakerConfig.ofDefaults(), BulkheadConfig.ofDefaults(),
            ref -> {
              if (!"mw.partner-secrets.acme.api-key".equals(ref.path())) {
                throw new IllegalStateException("no secret stored under " + ref.path());
              }
              return SECRET;
            },
            Clock.systemUTC());
    return framework.gateway;
  }

  static PartnerCall pay(String partnerId) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("amount", Map.of("valueMinor", 2000L, "currency", "BDT", "major", "20.00"));
    input.put("creditParty", Map.of("type", "MSISDN", "id", "01711000001"));
    return new PartnerCall(partnerId, "PAY", "BILL_PAY", "txn-1", "corr-1", "UPAYREF1", input);
  }

  void stubPay(int status, String body) {
    partner.stubFor(
        post(urlEqualTo("/v2/payments"))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void aRouteTemplateIsInstantiatedPurelyFromAConfigRecordAndCallsThePartner() {
    stubPay(200, "{\"result\":\"OK\",\"id\":\"ACME-77\"}");
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

    PartnerResponse response = gateway.call(pay("acme"));

    assertThat(framework.camel.getRoute("partner-acme")).isNotNull();
    assertThat(response).isEqualTo(new PartnerResponse.Success("ACME-77", null));
    partner.verify(
        1,
        postRequestedFor(urlEqualTo("/v2/payments"))
            .withHeader("X-Acme-Key", equalTo(SECRET))
            .withHeader("X-Channel", equalTo("upay"))
            .withHeader("X-Correlation-Id", equalTo("corr-1"))
            .withHeader("Content-Type", matching("application/json.*"))
            .withRequestBody(equalToJson("{\"ref\":\"UPAYREF1\",\"to\":\"01711000001\",\"minor\":2000}")));
  }

  @Test
  void aSuccessCarriesTheMappedAttributesAsText() {
    // billpay extension: a read (bill fetch) returns data, not only a reference
    stubPay(200, "{\"result\":\"OK\",\"id\":\"ACME-78\",\"due\":667,\"period\":\"2026-11\"}");
    String withData =
        "{\"outcome\": \"SUCCESS\", \"partnerRef\": body.id, "
            + "\"attributes\": {\"amount\": body.due, \"billPeriod\": body.period, \"missing\": body.nope}}";
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, withData)), null);

    assertThat(gateway.call(pay("acme")))
        .isEqualTo(new PartnerResponse.Success("ACME-78", null, Map.of("amount", "667", "billPeriod", "2026-11")));
  }

  @Test
  void errorCodesAreMappedByTheConfiguredMapAndAmbiguousCodesAreNotRejections() {
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

    stubPay(400, "{\"result\":\"ERR\",\"error\":\"E17\",\"text\":\"closed\"}");
    assertThat(gateway.call(pay("acme")))
        .isEqualTo(new PartnerResponse.Rejected("E17", "closed", "ACCOUNT_CLOSED", false));

    stubPay(400, "{\"result\":\"ERR\",\"error\":\"E99\",\"text\":\"?\"}");
    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.Ambiguous("E99", "?"));
  }

  @Test
  void aMappingThatRejectsOnAStatusNotListedAsDefiniteIsOverruledToUnknown() {
    // the mapping says FAILED for any "ERR" body, but 403 is not a definite status: fail closed
    stubPay(403, "{\"result\":\"ERR\",\"error\":\"E17\"}");
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));
  }

  @Test
  void aMappingThatClaimsSuccessOnANon2xxIsOverruledToUnknown() {
    stubPay(409, "{\"result\":\"OK\",\"id\":\"X\"}");
    String alwaysSuccess = "{\"outcome\": \"SUCCESS\", \"partnerRef\": body.id}";
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, alwaysSuccess)), null);

    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));
  }

  @Test
  void aRedirectIsNeverFollowedSoNeitherTheRequestNorItsCredentialsLeaveForAnotherHost() {
    WireMockServer evil = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    evil.start();
    try {
      evil.stubFor(post(urlEqualTo("/steal")).willReturn(aResponse().withStatus(200).withBody("{\"result\":\"OK\",\"id\":\"X\"}")));
      partner.stubFor(
          post(urlEqualTo("/v2/payments"))
              .willReturn(aResponse().withStatus(307).withHeader("Location", "http://localhost:" + evil.port() + "/steal")));
      PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

      assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.Unknown(UnknownReason.UNEXPECTED_STATUS));
      evil.verify(0, anyRequestedFor(anyUrl()));
    } finally {
      evil.stop();
    }
  }

  @Test
  void pendingIsOnlyBelievedFromTheAsyncCallbackTemplate() {
    stubPay(202, "{\"id\":\"ACME-9\"}");

    PartnerGateway sync = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);
    assertThat(sync.call(pay("acme"))).isEqualTo(new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY));
    framework.close();

    PartnerGateway async = framework(Map.of("acme", acme(PartnerTemplates.ASYNC_CALLBACK, ACME_RESPONSE)), null);
    assertThat(async.call(pay("acme"))).isEqualTo(new PartnerResponse.Pending("ACME-9", null));
  }

  @Test
  void aCallbackIsVerifiedByTheAuthSchemeThenMapped() {
    PartnerConfig base = acme(PartnerTemplates.ASYNC_CALLBACK, ACME_RESPONSE);
    Map<String, PartnerConfig.Operation> ops = new HashMap<>(base.operations());
    ops.put(
        "CALLBACK",
        new PartnerConfig.Operation(
            "POST", "/partner/acme/callback", List.of(), null,
            "{\"reference\": body.ref, \"partnerRef\": body.id, \"outcome\": body.result = \"OK\" ? \"SUCCESS\" : \"FAILED\", \"code\": body.error}"));
    PartnerConfig hmac =
        new PartnerConfig(
            true, base.template(), base.baseUrl(), null, null, null, Map.of(),
            new PartnerConfig.Auth("hmac-sha256", "api-key", Map.of()),
            ops, base.errorMap(), base.ambiguousCodes(), base.sensitiveFields(), null);
    PartnerGateway gateway = framework(Map.of("acme", hmac), null);
    String body = "{\"ref\":\"UPAYREF1\",\"id\":\"ACME-9\",\"result\":\"OK\"}";

    InboundResult result = gateway.inbound(signed(body, body));

    assertThat(result).isEqualTo(new InboundResult("ACME-9", "UPAYREF1", "SUCCESS", null, null, Map.of()));
    assertThatThrownBy(() -> gateway.inbound(signed(body, body.replace("OK", "NO"))))
        .isInstanceOf(PartnerAuthenticationException.class);
  }

  @Test
  void aRequestReplyPartnerTakesNoCallbacks() {
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

    assertThatThrownBy(() -> gateway.inbound(signed("{}", "{}"))).isInstanceOf(PartnerUnsupportedException.class);
  }

  /** A callback signed over {@code signedBody} but carrying {@code sentBody}. */
  static InboundCall signed(String signedBody, String sentBody) {
    String ts = Long.toString(Instant.now().getEpochSecond());
    String sig =
        HmacSha256AuthScheme.sign(SECRET, "POST", "/partner/acme/callback", ts, signedBody.getBytes(StandardCharsets.UTF_8));
    return new InboundCall(
        "acme", "POST", "/partner/acme/callback", Map.of("X-Signature", sig, "X-Timestamp", ts),
        sentBody.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aPartnerWithoutConfigUsesTheDefaultInstanceAndWithoutADefaultIsNotSent() {
    stubPay(200, "{\"result\":\"OK\",\"id\":\"ACME-1\"}");
    PartnerGateway withDefault = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), "acme");
    assertThat(withDefault.call(pay("other"))).isInstanceOf(PartnerResponse.Success.class);
    framework.close();

    PartnerGateway noDefault = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);
    assertThat(noDefault.call(pay("other"))).isEqualTo(new PartnerResponse.NotSent(NotSentReason.DISABLED));
    partner.verify(1, anyRequestedFor(anyUrl()));
  }

  @Test
  void aDisabledPartnerAnUnconfiguredOperationAndMissingCredentialsAreNotSent() {
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

    PartnerCall inquiry = new PartnerCall("acme", "STATUS_QUERY", "BILL_PAY", "t", "c", "R", Map.of());
    assertThat(gateway.call(inquiry)).isEqualTo(new PartnerResponse.NotSent(NotSentReason.MISCONFIGURED));

    apply("acme", 2, withAuthRef(acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE), "no-such-secret"));
    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.NotSent(NotSentReason.MISCONFIGURED));

    apply("acme", 3, disabled(acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)));
    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.NotSent(NotSentReason.DISABLED));

    partner.verify(0, anyRequestedFor(anyUrl()));
  }

  // --- config changes without restart ------------------------------------------------------------

  @Test
  void aChangedMappingArrivingAsAConfigChangedEventIsUsedByTheNextCall() {
    stubPay(200, "{\"result\":\"OK\",\"id\":\"ACME-1\"}");
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);
    gateway.call(pay("acme"));

    PartnerConfig v2 = acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE);
    Map<String, PartnerConfig.Operation> ops =
        Map.of(
            "PAY",
            new PartnerConfig.Operation(
                "POST", "/v2/payments", List.of(400), "{\"reference\": reference, \"amount\": amount.major}",
                ACME_RESPONSE));
    apply("acme", 2, withOperations(v2, ops));
    gateway.call(pay("acme"));

    List<String> bodies =
        partner.findAll(postRequestedFor(urlEqualTo("/v2/payments"))).stream().map(r -> r.getBodyAsString()).toList();
    assertThat(bodies).hasSize(2);
    assertThat(bodies.get(0)).contains("\"minor\"");
    assertThat(bodies.get(1)).isEqualTo("{\"reference\":\"UPAYREF1\",\"amount\":\"20.00\"}");
  }

  @Test
  void aLoweredTimeoutArrivingAsAConfigChangedEventIsUsedByTheNextCall() {
    partner.stubFor(
        post(urlEqualTo("/v2/payments"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(1200).withBody("{\"result\":\"OK\",\"id\":\"A\"}")));
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);
    assertThat(gateway.call(pay("acme"))).isInstanceOf(PartnerResponse.Success.class);

    apply("acme", 2, acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE).withTimeoutMs(300));

    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.Unknown(UnknownReason.TIMEOUT));
  }

  @Test
  void aTemplateChangeRecreatesTheRouteAndADeleteRemovesIt() {
    stubPay(202, "{\"id\":\"ACME-9\"}");
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

    apply("acme", 2, acme(PartnerTemplates.ASYNC_CALLBACK, ACME_RESPONSE));
    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.Pending("ACME-9", null));

    framework.store.apply(event("acme", 3, ConfigChangedEvent.Change.DELETE, null));
    assertThat(framework.camel.getRoute("partner-acme")).isNull();
    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.NotSent(NotSentReason.DISABLED));
  }

  @Test
  void aNewPartnerArrivingAsAConfigChangedEventGetsItsOwnRoute() {
    stubPay(200, "{\"result\":\"OK\",\"id\":\"ACME-2\"}");
    PartnerGateway gateway = framework(Map.of(), null);
    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.NotSent(NotSentReason.DISABLED));

    apply("acme", 1, acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE));

    assertThat(framework.camel.getRoute("partner-acme")).isNotNull();
    assertThat(gateway.call(pay("acme"))).isEqualTo(new PartnerResponse.Success("ACME-2", null));
  }

  // --- wrapper: audit and metrics ------------------------------------------------------------------

  @Test
  void theWrapperAuditsTheRawRequestAndResponseMasked() {
    stubPay(200, "{\"result\":\"OK\",\"id\":\"ACME-77\",\"echo\":\"paid 01711000001\"}");
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

    gateway.call(pay("acme"));

    assertThat(framework.audits).hasSize(1);
    PartnerAudit.Entry e = framework.audits.getFirst();
    assertThat(e.partnerId()).isEqualTo("acme");
    assertThat(e.operation()).isEqualTo("PAY");
    assertThat(e.txnId()).isEqualTo("txn-1");
    assertThat(e.method()).isEqualTo("POST");
    assertThat(e.path()).isEqualTo("/v2/payments");
    assertThat(e.httpStatus()).isEqualTo(200);
    assertThat(e.outcome()).isEqualTo("SUCCESS");
    assertThat(e.request()).contains("\"to\":\"0171****001\"").doesNotContain("01711000001").contains("UPAYREF1");
    assertThat(e.response()).contains("ACME-77").contains("0171****001").doesNotContain("01711000001");
    assertThat(e.request() + e.response()).doesNotContain(SECRET);
  }

  @Test
  void theWrapperRecordsThePartnerCallMetrics() {
    stubPay(200, "{\"result\":\"OK\",\"id\":\"ACME-77\"}");
    PartnerGateway gateway = framework(Map.of("acme", acme(PartnerTemplates.REQUEST_REPLY, ACME_RESPONSE)), null);

    gateway.call(pay("acme"));
    stubPay(500, "{}");
    gateway.call(pay("acme"));

    var meters = framework.meters;
    assertThat(meters.get("mw.partner.call").tags("partner", "acme", "txn_type", "BILL_PAY", "status", "SUCCESS").counter().count())
        .isEqualTo(1.0);
    assertThat(meters.get("mw.partner.call").tags("partner", "acme", "status", "UNKNOWN").counter().count())
        .isEqualTo(1.0);
    assertThat(meters.get("mw.partner.call.duration").tags("partner", "acme").timers())
        .hasSize(2)
        .allSatisfy(t -> assertThat(t.count()).isEqualTo(1));
    assertThat(meters.get("mw.partner.circuit.state").tags("partner", "acme").gauge().value()).isEqualTo(0.0);
  }

  private void apply(String id, long version, PartnerConfig config) {
    Map<String, Object> value =
        PartnerFramework.JSON.convertValue(config, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    assertThat(framework.store.apply(event(id, version, ConfigChangedEvent.Change.UPSERT, value)))
        .as("config v%s applied", version)
        .isTrue();
  }

  static ConfigChangedEvent event(String id, long version, ConfigChangedEvent.Change change, Map<String, Object> value) {
    return new ConfigChangedEvent(
        1, "partner/" + id, ConfigChangedEvent.ConfigType.PARTNER, change, version, value,
        Instant.parse("2026-11-04T10:00:00Z"), "ops.maker1", "ops.checker1", "corr-cfg");
  }

  static PartnerConfig withOperations(PartnerConfig c, Map<String, PartnerConfig.Operation> ops) {
    return new PartnerConfig(
        c.enabled(), c.template(), c.baseUrl(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(),
        c.headers(), c.auth(), ops, c.errorMap(), c.ambiguousCodes(), c.sensitiveFields(), c.resilience());
  }

  static PartnerConfig withAuthRef(PartnerConfig c, String ref) {
    return new PartnerConfig(
        c.enabled(), c.template(), c.baseUrl(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(),
        c.headers(), new PartnerConfig.Auth(c.auth().scheme(), ref, c.auth().params()), c.operations(),
        c.errorMap(), c.ambiguousCodes(), c.sensitiveFields(), c.resilience());
  }

  static PartnerConfig disabled(PartnerConfig c) {
    return new PartnerConfig(
        false, c.template(), c.baseUrl(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(),
        c.headers(), c.auth(), c.operations(), c.errorMap(), c.ambiguousCodes(), c.sensitiveFields(), c.resilience());
  }
}
