package mfs.mw.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import mfs.mw.checkout.client.AuthClient;
import mfs.mw.checkout.client.FakeAuth;
import mfs.mw.checkout.dto.TxnAuthorisationRequest;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.messaging.KafkaTestConsumer;
import mfs.mw.checkout.messaging.OrchestratorStub;
import mfs.mw.checkout.security.ResultSigner;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Web checkout end to end inside the module (spec 9.3, tickets 31, 72): the merchant API as APISIX
 * forwards it after {@code hmac-auth} (consumer name in {@code X-Mw-Merchant-Id}), the hosted
 * page API, real PostgreSQL and Kafka (MERCHANT_PAY on {@code mw.txn.request.v1}), mw-auth played by
 * {@link FakeAuth} behind a mocked {@link AuthClient} (the OTP, the PIN and the assertion; its own
 * rules are tested in mw-auth), and a stub playing mw-orchestrator whose answer depends on the amount
 * ({@link OrchestratorStub}).
 */
@SpringBootTest(
    properties = {
      "mw.checkout.instance-id=it-checkout-1",
      "mw.checkout.consumers.enabled=true",
      "mw.checkout.reply-budget=3s",
      "mw.outbox.relay.enabled=true"
    })
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, CheckoutFlowIT.Fixtures.class})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutFlowIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String MERCHANT = "merchant_demo";
  static final String SECRET = "test-merchant-secret-not-real";
  static final String CUSTOMER = "8801711000001";
  static final String PAGE = "https://checkout.example.test/pay/";

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.now());
    }
  }

  private final MockMvcTester http;
  private final MutableClock clock;
  private OrchestratorStub orchestrator;
  private final FakeAuth fake = new FakeAuth();

  @MockitoBean AuthClient auth;

  @Autowired
  CheckoutFlowIT(MockMvcTester http, MutableClock clock) {
    this.http = http;
    this.clock = clock;
  }

  @BeforeAll
  void startStub() {
    TestTopics.ensure("mw.txn.request.v1", "mw.txn.status.v1");
    orchestrator = new OrchestratorStub(TestTopics.bootstrap());
  }

  @BeforeEach
  void consumersReady() {
    fake.install(auth);
    TestTopics.awaitGroupStable("mw-checkout.reply.it-checkout-1", Duration.ofSeconds(60));
    TestTopics.awaitGroupStable("mw-checkout", Duration.ofSeconds(60));
  }

  @AfterAll
  void stopStub() {
    orchestrator.close();
  }

  // ---- the happy path -----------------------------------------------------------------------

  @Test
  void aMerchantCreatesASessionAndTheCustomerPaysWithOtpAndPin() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    Instant before = clock.instant();
    MvcTestResult created = createSession(MERCHANT, orderId, OrchestratorStub.COMPLETES);
    assertThat(created).hasStatus(201);
    JsonNode s = body(created);
    String sessionId = s.path("sessionId").asString();
    assertThat(s.path("status").asString()).isEqualTo("OPEN");
    assertThat(Instant.parse(s.path("expiresAt").asString())).isEqualTo(before.plus(Duration.ofMinutes(15)));
    String checkoutUrl = s.path("checkoutUrl").asString();
    assertThat(checkoutUrl).startsWith(PAGE);
    String token = checkoutUrl.substring(PAGE.length());
    assertThat(token).hasSizeGreaterThanOrEqualTo(43).doesNotContain(sessionId);

    JsonNode page = body(http.get().uri("/checkout/pay/v1/{t}", token).exchange());
    assertThat(page.path("merchantName").asString()).isEqualTo("Karim Traders");
    assertThat(page.path("amount").path("valueMinor").asLong()).isEqualTo(OrchestratorStub.COMPLETES);
    assertThat(page.path("status").asString()).isEqualTo("OPEN");
    assertThat(page.path("pinKey").path("keyId").asString()).isEqualTo(FakeAuth.KEY_ID);
    assertThat(page.path("pinKey").path("algorithm").asString()).isEqualTo("RSA-OAEP-SHA-256");
    assertThat(page.path("pinKey").path("publicKey").asString()).startsWith("-----BEGIN PUBLIC KEY-----");

    String otp = requestOtp(token, sessionId, 1);

    MvcTestResult wrong = confirm(token, otp.equals("000000") ? "111111" : "000000");
    assertThat(wrong).hasStatus(401);
    assertThat(body(wrong).path("code").asString()).isEqualTo("MW-AUTH-010");
    assertThat(fake.authorisations()).as("no PIN check before the OTP matched").isEmpty();

    MvcTestResult paid = confirm(token, otp);
    assertThat(paid).hasStatusOk();
    JsonNode result = body(paid);
    assertThat(result.path("status").asString()).isEqualTo("PAID");
    String txnId = result.path("txnId").asString();
    assertThat(txnId).isNotBlank();
    URI redirect = URI.create(result.path("redirectUrl").asString());
    assertThat(redirect.toString()).startsWith("https://shop.example.test/ok?");
    Map<String, String> q = query(redirect);
    assertThat(q).containsEntry("status", "PAID").containsEntry("orderId", orderId)
        .containsEntry("sessionId", sessionId).containsEntry("txnId", txnId)
        .containsEntry("amount", Long.toString(OrchestratorStub.COMPLETES));
    assertThat(ResultSigner.verify(SECRET, q)).as("the merchant verifies the signature").isTrue();

    List<ConsumerRecord<String, String>> requests =
        orchestrator.requests().stream().filter(r -> r.key().equals(txnId)).toList();
    assertThat(requests).as("one MERCHANT_PAY, none for the wrong OTP").hasSize(1);
    JsonNode req = JSON.readTree(requests.getFirst().value());
    assertThat(req.path("txnType").asString()).isEqualTo("MERCHANT_PAY");
    assertThat(req.path("channel").asString()).isEqualTo("WEB_CHECKOUT");
    assertThat(req.path("debitParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(req.path("creditParty").path("id").asString()).isEqualTo("01911000001");
    assertThat(req.path("assertion").asString()).isEqualTo(FakeAuth.ASSERTION);
    assertThat(req.has("encryptedPin")).as("no PIN material on Kafka").isFalse();
    assertThat(KafkaTestConsumer.headers(requests.getFirst())).containsEntry("mw-reply-to", "it-checkout-1");

    // mw-auth was asked with exactly the published values (else the core's txnHash check fails)
    assertThat(fake.authorisations()).hasSize(1);
    TxnAuthorisationRequest a = fake.authorisations().getFirst();
    assertThat(a.msisdn()).isEqualTo(req.path("initiator").path("id").asString());
    assertThat(a.channel()).isEqualTo(req.path("channel").asString());
    assertThat(a.encryptedPin().keyId()).isEqualTo(FakeAuth.KEY_ID);
    assertThat(a.txn().txnType()).isEqualTo(req.path("txnType").asString());
    assertThat(a.txn().amount()).isEqualTo(req.path("amount").path("valueMinor").asLong());
    assertThat(a.txn().currency()).isEqualTo(req.path("amount").path("currency").asString());
    assertThat(a.txn().payer().type()).isEqualTo(req.path("debitParty").path("type").asString());
    assertThat(a.txn().payer().id()).isEqualTo(req.path("debitParty").path("id").asString());
    assertThat(a.txn().payee().type()).isEqualTo(req.path("creditParty").path("type").asString());
    assertThat(a.txn().payee().id()).isEqualTo(req.path("creditParty").path("id").asString());
    assertThat(a.txn().idempotencyKey()).isEqualTo(req.path("idempotencyKey").asString());

    JsonNode view = body(merchantGet(MERCHANT, sessionId));
    assertThat(view.path("status").asString()).isEqualTo("PAID");
    assertThat(view.path("txnId").asString()).isEqualTo(txnId);
    assertThat(view.path("orderId").asString()).isEqualTo(orderId);
    assertThat(view.path("payer").asString()).isEqualTo("0171****001");

    MvcTestResult back = http.get().uri("/checkout/pay/v1/{t}/return", token).exchange();
    assertThat(back).hasStatus(303);
    URI location = URI.create(back.getResponse().getHeader("Location"));
    assertThat(ResultSigner.verify(SECRET, query(location))).isTrue();
    assertThat(query(location)).containsEntry("status", "PAID");

    assertThat(merchantGet("merchant_other", sessionId)).hasStatus(404);
    MvcTestResult again = confirm(token, otp);
    assertThat(again).hasStatus(409);
    assertThat(body(again).path("code").asString()).isEqualTo("MW-VAL-021");
  }

  // ---- expiry -------------------------------------------------------------------------------

  @Test
  void anExpiredSessionCannotBePaid() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String otp = requestOtp(token, s.path("sessionId").asString(), 1);

    // later tests create their sessions at the advanced time; nothing depends on the wall clock
    clock.advance(Duration.ofMinutes(15).plusSeconds(1));

    MvcTestResult pay = confirm(token, otp);
    assertThat(pay).hasStatus(410);
    assertThat(body(pay).path("code").asString()).isEqualTo("MW-VAL-020");
    assertThat(postOtp(token)).hasStatus(410);
    assertThat(body(http.get().uri("/checkout/pay/v1/{t}", token).exchange()).path("status").asString())
        .isEqualTo("EXPIRED");
    assertThat(body(merchantGet(MERCHANT, s.path("sessionId").asString())).path("status").asString())
        .isEqualTo("EXPIRED");
    MvcTestResult back = http.get().uri("/checkout/pay/v1/{t}/return", token).exchange();
    assertThat(back).hasStatus(303);
    Map<String, String> q = query(URI.create(back.getResponse().getHeader("Location")));
    assertThat(q).containsEntry("status", "EXPIRED");
    assertThat(ResultSigner.verify(SECRET, q)).isTrue();
  }

  // ---- mw-auth's answers (ticket 72) ---------------------------------------------------------

  @Test
  void theOtpGoesThroughMwAuthBoundToTheSession() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String sessionId = s.path("sessionId").asString();
    requestOtp(token, sessionId, 1);
    org.mockito.Mockito.verify(auth).sendOtp("01711000001", UUID.fromString(sessionId), "en", "10.00", "Karim Traders");
  }

  @Test
  void aWrongPinIsMwAuthsAnswerAndTheSessionStaysOpen() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String sessionId = s.path("sessionId").asString();
    String otp = requestOtp(token, sessionId, 1);

    MvcTestResult wrongPin = confirm(token, otp, "d3Jvbmc=");
    assertThat(wrongPin).hasStatus(401);
    JsonNode e = body(wrongPin);
    assertThat(e.path("code").asString()).isEqualTo("MW-AUTH-040");
    assertThat(e.path("details").get(0).path("field").asString()).isEqualTo("triesLeft");
    assertThat(body(http.get().uri("/checkout/pay/v1/{t}", token).exchange()).path("status").asString())
        .isEqualTo("OPEN");
    assertThat(orchestrator.requests().stream().filter(r -> r.value().contains("checkout:" + sessionId)))
        .as("nothing published").isEmpty();

    // the OTP was spent by the check: the next try needs a new one
    assertThat(body(confirm(token, otp)).path("code").asString()).isEqualTo("MW-AUTH-011");
    String second = requestOtp(token, sessionId, 2);
    assertThat(body(confirm(token, second)).path("status").asString()).isEqualTo("PAID");
  }

  @Test
  void aPinLockAndAnUnreachableMwAuthPassThroughAndNothingIsPaid() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String sessionId = s.path("sessionId").asString();

    fake.refuseNext(new MwException("MW-AUTH-041", "PIN locked").withStatus(HttpStatus.LOCKED)
        .withDetails(List.of(new mfs.mw.checkout.dto.ErrorDetail("retryAfter", "60"))));
    MvcTestResult locked = confirm(token, requestOtp(token, sessionId, 1));
    assertThat(locked).hasStatus(423);
    assertThat(body(locked).path("code").asString()).isEqualTo("MW-AUTH-041");
    assertThat(body(locked).path("details").get(0).path("issue").asString()).isEqualTo("60");

    fake.refuseNext(new MwException("MW-SYS-012", "Service temporarily unavailable, try again")
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE).withRetryable(true));
    MvcTestResult down = confirm(token, requestOtp(token, sessionId, 2));
    assertThat(down).hasStatus(503);
    assertThat(body(down).path("code").asString()).isEqualTo("MW-SYS-012");
    assertThat(body(down).path("retryable").asBoolean()).isTrue();

    assertThat(body(http.get().uri("/checkout/pay/v1/{t}", token).exchange()).path("status").asString())
        .isEqualTo("OPEN");
    assertThat(orchestrator.requests().stream().filter(r -> r.value().contains("checkout:" + sessionId)))
        .isEmpty();
  }

  @Test
  void aSessionSendsAtMostThreeOtps() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    for (int i = 0; i < 3; i++) {
      assertThat(postOtp(token)).hasStatus(202);
    }
    MvcTestResult fourth = postOtp(token);
    assertThat(fourth).hasStatus(429);
    assertThat(body(fourth).path("code").asString()).isEqualTo("MW-VAL-022");
  }

  @Test
  void paymentWithoutAnOtpIsRefused() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    MvcTestResult pay = confirm(token, "123456");
    assertThat(pay).hasStatus(401);
    assertThat(body(pay).path("code").asString()).isEqualTo("MW-AUTH-011");
  }

  // ---- failures and late answers -----------------------------------------------------------

  @Test
  void aCoreRejectionFailsTheSessionAndSendsTheBrowserToTheFailUrl() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.CORE_REJECTS));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String otp = requestOtp(token, s.path("sessionId").asString(), 1);

    MvcTestResult pay = confirm(token, otp);
    assertThat(pay).hasStatusOk();
    JsonNode r = body(pay);
    assertThat(r.path("status").asString()).isEqualTo("FAILED");
    assertThat(r.path("error").path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    Map<String, String> q = query(URI.create(r.path("redirectUrl").asString()));
    assertThat(r.path("redirectUrl").asString()).startsWith("https://shop.example.test/fail?");
    assertThat(q).containsEntry("status", "FAILED").containsEntry("errorCode", "MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(ResultSigner.verify(SECRET, q)).isTrue();
    assertThat(body(merchantGet(MERCHANT, s.path("sessionId").asString())).path("status").asString())
        .isEqualTo("FAILED");
  }

  @Tag("slow-wait")
  @Test
  void anAnswerAfterTheBudgetIsPickedUpFromTheStatusTopic() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.SILENT));
    String sessionId = s.path("sessionId").asString();
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String otp = requestOtp(token, sessionId, 1);

    MvcTestResult pay = confirm(token, otp);
    assertThat(pay).hasStatus(202);
    JsonNode r = body(pay);
    assertThat(r.path("status").asString()).isEqualTo("PAYING");
    String txnId = r.path("txnId").asString();
    assertThat(http.get().uri("/checkout/pay/v1/{t}/return", token).exchange()).hasStatus(409);

    publishCompleted(JSON.readTree(
        orchestrator.requests().stream().filter(x -> x.key().equals(txnId)).findFirst().orElseThrow().value()));

    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    String status = "PAYING";
    while (!status.equals("PAID") && System.nanoTime() < deadline) {
      Thread.sleep(200);
      status = body(merchantGet(MERCHANT, sessionId)).path("status").asString();
    }
    assertThat(status).isEqualTo("PAID");
    JsonNode page = body(http.get().uri("/checkout/pay/v1/{t}", token).exchange());
    assertThat(ResultSigner.verify(SECRET, query(URI.create(page.path("redirectUrl").asString())))).isTrue();
  }

  @Test
  void theCustomerCancels() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());

    MvcTestResult cancel = http.post().uri("/checkout/pay/v1/{t}/cancel", token).exchange();
    assertThat(cancel).hasStatusOk();
    JsonNode r = body(cancel);
    assertThat(r.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(r.path("redirectUrl").asString()).startsWith("https://shop.example.test/cancel?");
    assertThat(ResultSigner.verify(SECRET, query(URI.create(r.path("redirectUrl").asString())))).isTrue();
    assertThat(postOtp(token)).hasStatus(409);
  }

  // ---- merchant API ------------------------------------------------------------------------

  @Test
  void theSameOrderReturnsItsSessionAndAnotherAmountIsAConflict() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    JsonNode first = body(createSession(MERCHANT, orderId, 1500));
    MvcTestResult repeat = createSession(MERCHANT, orderId, 1500);
    assertThat(repeat).hasStatusOk();
    assertThat(body(repeat).path("sessionId").asString()).isEqualTo(first.path("sessionId").asString());
    assertThat(body(repeat).path("checkoutUrl").asString()).isEqualTo(first.path("checkoutUrl").asString());

    MvcTestResult other = createSession(MERCHANT, orderId, 1600);
    assertThat(other).hasStatus(409);
    assertThat(body(other).path("code").asString()).isEqualTo("MW-IDEM-002");

    assertThat(createSession("merchant_other", orderId, 1600)).as("orders are per merchant").hasStatus(201);
  }

  @Test
  void requestsWithoutAnAuthenticatedMerchantAreRefused() throws Exception {
    MvcTestResult none = createSession(null, "ORD-1", 1000);
    assertThat(none).hasStatus(401);
    assertThat(body(none).path("code").asString()).isEqualTo("MW-AUTH-001");
    MvcTestResult unknown = createSession("merchant_nobody", "ORD-1", 1000);
    assertThat(unknown).hasStatus(403);
    assertThat(body(unknown).path("code").asString()).isEqualTo("MW-AUTH-002");
    assertThat(merchantGet(null, UUID.randomUUID().toString())).hasStatus(401);
  }

  @Test
  void invalidSessionRequestsAreRefusedWithTheFieldNamed() throws Exception {
    ObjectNode b = sessionBody("ORD 1", 1000);
    b.put("successUrl", "http://shop.example.test/ok");
    MvcTestResult r =
        http.post().uri("/checkout/v1/sessions").header("X-Mw-Merchant-Id", MERCHANT)
            .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(b)).exchange();
    assertThat(r).hasStatus(400);
    JsonNode e = body(r);
    assertThat(e.path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(e.path("details").valueStream().map(d -> d.path("field").asString()))
        .contains("orderId", "successUrl");
  }

  @Test
  void anIpnUrlOverPlainHttpOrAtAnInternalAddressIsRefused() throws Exception {
    for (String url : List.of("http://shop.example.test/ipn", "http://mw-simulators:8080/merchant/ipn",
        "https://169.254.169.254/latest/meta-data", "https://127.0.0.1/ipn", "https://[::1]/ipn")) {
      ObjectNode b = sessionBody("ORD-" + UUID.randomUUID(), 1000);
      b.put("ipnUrl", url);
      MvcTestResult r =
          http.post().uri("/checkout/v1/sessions").header("X-Mw-Merchant-Id", MERCHANT)
              .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(b)).exchange();
      assertThat(r).as(url).hasStatus(400);
      JsonNode e = body(r);
      assertThat(e.path("code").asString()).isEqualTo("MW-VAL-001");
      assertThat(e.path("details").valueStream().map(d -> d.path("field").asString())).containsExactly("ipnUrl");
    }
  }

  @Test
  void anUnknownTokenIsNotFound() throws Exception {
    assertThat(http.get().uri("/checkout/pay/v1/{t}", "x".repeat(43)).exchange()).hasStatus(404);
  }

  // ---- helpers ------------------------------------------------------------------------------

  static ObjectNode sessionBody(String orderId, long amount) {
    ObjectNode b = JSON.createObjectNode();
    b.put("orderId", orderId);
    b.putObject("amount").put("valueMinor", amount).put("currency", "BDT");
    b.put("successUrl", "https://shop.example.test/ok");
    b.put("failUrl", "https://shop.example.test/fail");
    b.put("cancelUrl", "https://shop.example.test/cancel");
    b.put("ipnUrl", "https://shop.example.test/ipn");
    b.put("locale", "en");
    return b;
  }

  MvcTestResult createSession(String merchant, String orderId, long amount) {
    var req = http.post().uri("/checkout/v1/sessions").contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(sessionBody(orderId, amount)));
    if (merchant != null) {
      req = req.header("X-Mw-Merchant-Id", merchant);
    }
    return req.exchange();
  }

  MvcTestResult merchantGet(String merchant, String sessionId) {
    var req = http.get().uri("/checkout/v1/sessions/{id}", sessionId);
    if (merchant != null) {
      req = req.header("X-Mw-Merchant-Id", merchant);
    }
    return req.exchange();
  }

  MvcTestResult postOtp(String token) {
    return http.post().uri("/checkout/pay/v1/{t}/otp", token).contentType(MediaType.APPLICATION_JSON)
        .content("{\"wallet\":\"" + CUSTOMER + "\"}").exchange();
  }

  /** Asks for an OTP and reads the one mw-auth (the fake) sent for this wallet and session. */
  String requestOtp(String token, String sessionId, int sendNo) throws Exception {
    MvcTestResult r = postOtp(token);
    assertThat(r).as("OTP %s", sendNo).hasStatus(202);
    JsonNode sent = body(r);
    assertThat(sent.path("wallet").asString()).isEqualTo("0171****001");
    assertThat(sent.path("sendsLeft").asInt()).isEqualTo(3 - sendNo);
    String otp = fake.otp("01711000001", sessionId);
    assertThat(otp).matches("[0-9]{6}");
    return otp;
  }

  MvcTestResult confirm(String token, String otp) {
    return confirm(token, otp, FakeAuth.RIGHT_PIN);
  }

  MvcTestResult confirm(String token, String otp, String ciphertext) {
    ObjectNode b = JSON.createObjectNode();
    b.put("otp", otp);
    b.putObject("encryptedPin").put("keyId", FakeAuth.KEY_ID).put("ciphertext", ciphertext);
    return http.post().uri("/checkout/pay/v1/{t}/confirm", token).contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(b)).exchange();
  }

  static JsonNode body(MvcTestResult r) throws Exception {
    return JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  static Map<String, String> query(URI url) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : url.getRawQuery().split("&")) {
      int eq = pair.indexOf('=');
      out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  /** The orchestrator's COMPLETED, published long after the reply budget, with no reply-to. */
  static void publishCompleted(JsonNode req) {
    ObjectNode s = JSON.createObjectNode();
    s.put("schemaVersion", 1);
    s.put("txnId", req.path("txnId").asString());
    s.put("idempotencyKey", req.path("idempotencyKey").asString());
    s.put("correlationId", req.path("correlationId").asString());
    s.put("channel", "WEB_CHECKOUT");
    s.put("txnType", "MERCHANT_PAY");
    s.put("status", "COMPLETED");
    s.set("initiator", req.path("initiator"));
    s.set("debitParty", req.path("debitParty"));
    s.set("creditParty", req.path("creditParty"));
    s.set("amount", req.path("amount"));
    s.put("requestedAt", req.path("requestedAt").asString());
    s.put("updatedAt", Instant.now().toString());
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestTopics.bootstrap());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      ProducerRecord<String, String> rec =
          new ProducerRecord<>("mw.txn.status.v1", req.path("txnId").asString(), JSON.writeValueAsString(s));
      for (String[] h : List.of(
          new String[] {"mw-correlation-id", req.path("correlationId").asString()},
          new String[] {"mw-txn-id", req.path("txnId").asString()},
          new String[] {"mw-idempotency-key", req.path("idempotencyKey").asString()},
          new String[] {"mw-source", "mw-orchestrator"},
          new String[] {"mw-schema-version", "1"})) {
        rec.headers().add(h[0], h[1].getBytes(StandardCharsets.UTF_8));
      }
      producer.send(rec);
      producer.flush();
    }
  }
}
