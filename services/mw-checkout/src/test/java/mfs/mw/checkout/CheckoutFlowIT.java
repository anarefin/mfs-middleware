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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Web checkout end to end inside the module (spec 9.3, ticket 31): the merchant API as APISIX
 * forwards it after {@code hmac-auth} (consumer name in {@code X-Mw-Merchant-Id}), the hosted
 * page API, real PostgreSQL and Kafka (OTP on {@code mw.notify.request.v1} through the outbox,
 * MERCHANT_PAY on {@code mw.txn.request.v1}), and a stub playing mw-orchestrator whose answer
 * depends on the amount ({@link OrchestratorStub}).
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

  @Autowired
  CheckoutFlowIT(MockMvcTester http, MutableClock clock) {
    this.http = http;
    this.clock = clock;
  }

  @BeforeAll
  void startStub() {
    TestTopics.ensure("mw.txn.request.v1", "mw.txn.status.v1", "mw.notify.request.v1");
    orchestrator = new OrchestratorStub(TestTopics.bootstrap());
  }

  @BeforeEach
  void consumersReady() {
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
    assertThat(page.path("merchantName").asString()).isEqualTo("Demo Shop");
    assertThat(page.path("amount").path("valueMinor").asLong()).isEqualTo(OrchestratorStub.COMPLETES);
    assertThat(page.path("status").asString()).isEqualTo("OPEN");
    assertThat(page.path("pinKey").path("keyId").asString()).isEqualTo("core-pin-test-1");

    String otp = requestOtp(token, sessionId, 1);

    MvcTestResult wrong = confirm(token, otp.equals("000000") ? "111111" : "000000");
    assertThat(wrong).hasStatus(401);
    assertThat(body(wrong).path("code").asString()).isEqualTo("MW-AUTH-010");

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
    assertThat(req.path("encryptedPin").path("ciphertext").asString()).isEqualTo("Y2lwaGVydGV4dA==");
    assertThat(KafkaTestConsumer.headers(requests.getFirst())).containsEntry("mw-reply-to", "it-checkout-1");

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

  @Test
  void anExpiredOtpIsRefused() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String otp = requestOtp(token, s.path("sessionId").asString(), 1);
    clock.advance(Duration.ofMinutes(2).plusSeconds(1));

    MvcTestResult pay = confirm(token, otp);
    assertThat(pay).hasStatus(401);
    assertThat(body(pay).path("code").asString()).isEqualTo("MW-AUTH-011");
  }

  // ---- OTP rules ----------------------------------------------------------------------------

  @Test
  void afterThreeWrongOtpsEvenTheRightOneIsRefusedUntilANewOneIsRequested() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String sessionId = s.path("sessionId").asString();
    String otp = requestOtp(token, sessionId, 1);
    String wrong = otp.equals("000000") ? "111111" : "000000";
    for (int i = 0; i < 3; i++) {
      assertThat(body(confirm(token, wrong)).path("code").asString()).isEqualTo("MW-AUTH-010");
    }
    MvcTestResult locked = confirm(token, otp);
    assertThat(locked).hasStatus(401);
    assertThat(body(locked).path("code").asString()).isEqualTo("MW-AUTH-012");

    String second = requestOtp(token, sessionId, 2);
    assertThat(body(confirm(token, second)).path("status").asString()).isEqualTo("PAID");
  }

  @Test
  void parallelWrongOtpsAreCheckedAtMostThreeTimesAndTheRightOneIsThenRefused() throws Exception {
    JsonNode s = body(createSession(MERCHANT, "ORD-" + UUID.randomUUID(), OrchestratorStub.COMPLETES));
    String token = s.path("checkoutUrl").asString().substring(PAGE.length());
    String otp = requestOtp(token, s.path("sessionId").asString(), 1);
    // twenty distinct wrong guesses at once
    List<String> guesses = new java.util.ArrayList<>();
    for (int i = 0; guesses.size() < 20; i++) {
      String g = String.format("%06d", i);
      if (!g.equals(otp)) {
        guesses.add(g);
      }
    }
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    List<String> codes;
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(guesses.size())) {
      List<java.util.concurrent.Future<String>> answers = new java.util.ArrayList<>();
      for (String g : guesses) {
        answers.add(pool.submit(() -> {
          start.await();
          return body(confirm(token, g)).path("code").asString();
        }));
      }
      start.countDown();
      codes = new java.util.ArrayList<>();
      for (var a : answers) {
        codes.add(a.get());
      }
    }

    assertThat(codes.stream().filter("MW-AUTH-010"::equals).count()).as("wrong OTPs evaluated: %s", codes)
        .isEqualTo(3);
    assertThat(codes).allMatch(c -> c.equals("MW-AUTH-010") || c.equals("MW-AUTH-012"));
    MvcTestResult right = confirm(token, otp);
    assertThat(right).hasStatus(401);
    assertThat(body(right).path("code").asString()).isEqualTo("MW-AUTH-012");
    assertThat(orchestrator.requests().stream()
            .filter(r -> r.value().contains("checkout:" + s.path("sessionId").asString())))
        .as("no payment after lockout").isEmpty();
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

  /** Asks for an OTP and reads it from the SMS request the service sent to mw-notify. */
  String requestOtp(String token, String sessionId, int sendNo) throws Exception {
    MvcTestResult r = postOtp(token);
    assertThat(r).hasStatus(202);
    assertThat(body(r).path("wallet").asString()).isEqualTo("0171****001");
    String messageId = "checkout-otp:" + sessionId + ":" + sendNo;
    List<ConsumerRecord<String, String>> sms =
        KafkaTestConsumer.read(TestTopics.bootstrap(), "mw.notify.request.v1", 1, Duration.ofSeconds(20),
            x -> x.value().contains(messageId));
    assertThat(sms).as("OTP request %s", messageId).hasSize(1);
    JsonNode n = JSON.readTree(sms.getFirst().value());
    assertThat(n.path("priority").asString()).isEqualTo("OTP");
    assertThat(n.path("recipient").path("msisdn").asString()).isEqualTo("01711000001");
    String otp = n.path("params").path("otp").asString();
    assertThat(otp).matches("[0-9]{6}");
    return otp;
  }

  MvcTestResult confirm(String token, String otp) {
    ObjectNode b = JSON.createObjectNode();
    b.put("otp", otp);
    b.putObject("encryptedPin").put("keyId", "core-pin-test-1").put("ciphertext", "Y2lwaGVydGV4dA==");
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
