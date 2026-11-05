package mfs.mw.app.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.ValkeyInfrastructure;
import mfs.mw.app.gateway.dto.NotificationRequest;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.service.RequestSignatures;
import mfs.mw.app.gateway.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Device binding, signed requests, the nonce cache and sessions against real PostgreSQL and
 * Valkey (spec 9.1, 18.6, ticket 29), and the binding OTP (review A5). The transaction service is a
 * stub: what is under test is the gate in front of it. The Kafka publisher is a mock that hands the
 * test the OTP SMS, as the SMSC would hand it to the customer.
 */
@SpringBootTest(
    properties = {
      "mw.app-gateway.device-binding.enabled=true",
      "mw.app-gateway.device-binding.single-active-device=true",
      "mw.app-gateway.consumers.enabled=false",
      "mw.outbox.relay.enabled=false",
      // the parallel tests load the shared Testcontainers Valkey; the shipped 500 ms is for production
      "spring.data.redis.timeout=3s"
    })
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ValkeyInfrastructure.class})
class DeviceBindingIT {

  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String P2P =
      """
      {"txnType":"P2P","creditParty":{"id":"01711000002"},
       "amount":{"valueMinor":1000,"currency":"BDT"},
       "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVy"}}""";

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate valkey;

  @MockitoBean TransactionService transactions;
  @MockitoBean EventPublisher publisher;

  private String msisdn;
  private String subject;

  @Autowired
  DeviceBindingIT(MockMvcTester mvc, JdbcTemplate jdbc, StringRedisTemplate valkey) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.valkey = valkey;
  }

  /** A wallet of its own per test, so tests never see each other's devices or sessions. */
  @BeforeEach
  void freshWallet() {
    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    subject = "sub-" + UUID.randomUUID();
    jdbc.update("delete from app_device where wallet_id = ?", msisdn);
    jdbc.update("delete from app_device_otp where wallet_id = ?", msisdn);
    when(transactions.submit(any(), any(), any()))
        .thenReturn(SubmitResult.processing(TransactionView.processing(TXN_ID)));
  }

  record Device(String id, KeyPair key) {
    static Device create() throws Exception {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return new Device("dev-" + UUID.randomUUID(), g.generateKeyPair());
    }

    String publicKey() {
      return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }
  }

  /** The signature headers an app sends, signed with {@code device}'s key. */
  record Signed(String deviceId, String timestamp, String nonce, String signature) {}

  static Signed sign(Device device, String method, String path, String body) throws Exception {
    String ts = Long.toString(Instant.now().toEpochMilli());
    String nonce = "n-" + UUID.randomUUID();
    String canonical =
        String.join("\n", method, path, RequestSignatures.sha256Hex(body.getBytes(StandardCharsets.UTF_8)), ts, nonce,
            device.id());
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(device.key().getPrivate());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    return new Signed(device.id(), ts, nonce, Base64.getEncoder().encodeToString(s.sign()));
  }

  RequestPostProcessor token() {
    return jwt()
        .jwt(j -> j.subject(subject).claim("msisdn", msisdn)
            .claim("realm_access", Map.of("roles", List.of("customer"))));
  }

  MvcTestResult post(String path, String body, Signed signed, String idempotencyKey) {
    var req = mvc.post().uri(path).with(token()).contentType(MediaType.APPLICATION_JSON).content(body);
    if (signed != null) {
      req = req.header("X-Device-Id", signed.deviceId())
          .header("X-Timestamp", signed.timestamp())
          .header("X-Nonce", signed.nonce())
          .header("X-Signature", signed.signature());
    }
    if (idempotencyKey != null) {
      req = req.header("Idempotency-Key", idempotencyKey);
    }
    return req.exchange();
  }

  MvcTestResult askOtp(String deviceId) {
    var req = mvc.post().uri("/app/v1/devices/otp").with(token()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"locale\":\"en\"}");
    if (deviceId != null) {
      req = req.header("X-Device-Id", deviceId);
    }
    return req.exchange();
  }

  /** Asks for a binding OTP for {@code device} and returns the code the SMS carried. */
  String otpFor(Device device) {
    clearInvocations(publisher);
    assertThat(askOtp(device.id())).hasStatus(202);
    return lastSms().params().get("otp");
  }

  NotificationRequest lastSms() {
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(publisher, atLeastOnce()).publish(eq("mw.notify.request.v1"), eq(msisdn), any(), sent.capture());
    return (NotificationRequest) sent.getValue();
  }

  static String bindBody(Device device, String otp) {
    return "{\"publicKey\":\"" + device.publicKey() + "\",\"otp\":\"" + otp + "\"}";
  }

  MvcTestResult bindWith(Device device, String otp) throws Exception {
    String body = bindBody(device, otp);
    return post("/app/v1/devices", body, sign(device, "POST", "/app/v1/devices", body), null);
  }

  MvcTestResult bind(Device device) throws Exception {
    return bindWith(device, otpFor(device));
  }

  static String wrong(String otp) {
    return otp.equals("000000") ? "999999" : "000000";
  }

  MvcTestResult submitSigned(Device device) throws Exception {
    return post("/app/v1/transactions", P2P, sign(device, "POST", "/app/v1/transactions", P2P), "key-" + UUID.randomUUID());
  }

  MvcTestResult status(String deviceId) {
    var req = mvc.get().uri("/app/v1/transactions/" + TXN_ID).with(token());
    if (deviceId != null) {
      req = req.header("X-Device-Id", deviceId);
    }
    return req.exchange();
  }

  static void assertCode(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  @Test
  void aBoundDeviceSubmitsASignedP2p() throws Exception {
    Device d = Device.create();
    MvcTestResult bound = bind(d);
    assertThat(bound).hasStatus(201);
    assertThat(bound).bodyJson().extractingPath("$.deviceId").isEqualTo(d.id());

    assertThat(submitSigned(d)).hasStatus(202);
    verify(transactions, times(1)).submit(any(), any(), any());
  }

  @Test
  void theSameNonceReusedIsRejectedAndNothingIsSubmittedTwice() throws Exception {
    Device d = Device.create();
    bind(d);
    Signed signed = sign(d, "POST", "/app/v1/transactions", P2P);
    String key = "key-" + UUID.randomUUID();

    assertThat(post("/app/v1/transactions", P2P, signed, key)).hasStatus(202);
    assertCode(post("/app/v1/transactions", P2P, signed, key), 401, "MW-AUTH-006");
    verify(transactions, times(1)).submit(any(), any(), any());
  }

  @Test
  void aBindingRequestCannotBeReplayedEither() throws Exception {
    Device d = Device.create();
    String body = bindBody(d, otpFor(d));
    Signed signed = sign(d, "POST", "/app/v1/devices", body);
    assertThat(post("/app/v1/devices", body, signed, null)).hasStatus(201);
    assertCode(post("/app/v1/devices", body, signed, null), 401, "MW-AUTH-006");
  }

  @Test
  void aTamperedBodyIsRejected() throws Exception {
    Device d = Device.create();
    bind(d);
    Signed signed = sign(d, "POST", "/app/v1/transactions", P2P);
    assertCode(post("/app/v1/transactions", P2P.replace("1000", "9000"), signed, "key-t"), 401, "MW-AUTH-004");
  }

  /** The same JSON as {@link #P2P}, encoded differently (other key order, no spaces). */
  static final String P2P_REENCODED =
      "{\"amount\":{\"currency\":\"BDT\",\"valueMinor\":1000},\"txnType\":\"P2P\","
          + "\"encryptedPin\":{\"ciphertext\":\"Y2lwaGVy\",\"keyId\":\"core-pin-1\"},\"creditParty\":{\"id\":\"01711000002\"}}";

  @Test
  void aHashHeaderThatDoesNotMatchTheBodyIsIgnoredAndTheRequestRefused() throws Exception {
    Device d = Device.create();
    bind(d);
    Signed signed = sign(d, "POST", "/app/v1/transactions", P2P);
    String tampered = P2P.replace("1000", "9000");
    // a forged hash of the signed body next to a body the device never signed
    var r = mvc.post().uri("/app/v1/transactions").with(token()).contentType(MediaType.APPLICATION_JSON)
        .content(tampered)
        .header("X-Device-Id", signed.deviceId())
        .header("X-Timestamp", signed.timestamp())
        .header("X-Nonce", signed.nonce())
        .header("X-Signature", signed.signature())
        .header("X-Mw-Body-Sha256", RequestSignatures.sha256Hex(P2P.getBytes(StandardCharsets.UTF_8)))
        .header("Idempotency-Key", "key-" + UUID.randomUUID())
        .exchange();

    assertCode(r, 401, "MW-AUTH-004");
    verify(transactions, times(0)).submit(any(), any(), any());
  }

  @Test
  void theSignatureCoversTheExactBytesNotJustTheSameJson() throws Exception {
    Device d = Device.create();
    bind(d);
    Signed signed = sign(d, "POST", "/app/v1/transactions", P2P);
    assertCode(post("/app/v1/transactions", P2P_REENCODED, signed, "key-r"), 401, "MW-AUTH-004");
  }

  @Test
  void aSubmitBodyBreakingTheSchemaIsRefusedBeforeTheDeviceGate() {
    var r = mvc.post().uri("/app/v1/transactions").with(token()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"txnType\":\"P2P\",\"creditParty\":{\"id\":\"01711000002\"},"
            + "\"encryptedPin\":{\"keyId\":\"k1\",\"ciphertext\":\"x\"}}")
        .header("Idempotency-Key", "key-" + UUID.randomUUID())
        .exchange();
    assertCode(r, 400, "MW-VAL-001");
    verify(transactions, times(0)).submit(any(), any(), any());
  }

  @Test
  void anUnsignedSubmitIsRejected() throws Exception {
    Device d = Device.create();
    bind(d);
    var r = mvc.post().uri("/app/v1/transactions").with(token()).header("X-Device-Id", d.id())
        .header("Idempotency-Key", "key-u").contentType(MediaType.APPLICATION_JSON).content(P2P).exchange();
    assertCode(r, 401, "MW-AUTH-004");
  }

  @Test
  void anUnboundDeviceIsRejected() throws Exception {
    Device d = Device.create();
    assertCode(submitSigned(d), 401, "MW-AUTH-003");
    assertCode(status(d.id()), 401, "MW-AUTH-003");
    assertCode(status(null), 401, "MW-AUTH-003");
  }

  @Test
  void bindingANewDeviceReplacesTheOldOne() throws Exception {
    Device first = Device.create();
    Device second = Device.create();
    bind(first);
    bind(second);
    assertCode(submitSigned(first), 401, "MW-AUTH-003");
    assertThat(submitSigned(second)).hasStatus(202);
  }

  @Test
  void anExpiredSessionIsRejectedUntilASignedSessionOpen() throws Exception {
    Device d = Device.create();
    bind(d);
    when(transactions.find(any(), any())).thenReturn(TransactionView.processing(TXN_ID));
    assertThat(status(d.id())).hasStatus(200);
    assertThat(valkey.getExpire("sess:" + subject + ":" + d.id())).isPositive();

    valkey.delete("sess:" + subject + ":" + d.id()); // what the idle timeout does
    assertCode(status(d.id()), 401, "MW-AUTH-007");
    assertCode(submitSigned(d), 401, "MW-AUTH-007");

    assertThat(post("/app/v1/sessions", "", sign(d, "POST", "/app/v1/sessions", ""), null)).hasStatus(200);
    assertThat(status(d.id())).hasStatus(200);
  }

  @Test
  void aKeyThatIsNotP256IsRefusedAtBinding() throws Exception {
    Device d = Device.create();
    String body = "{\"publicKey\":\"bm90IGEga2V5\",\"otp\":\"" + otpFor(d) + "\"}";
    assertCode(post("/app/v1/devices", body, sign(d, "POST", "/app/v1/devices", body), null), 400, "MW-VAL-001");
  }

  @Test
  void aBindingSignedByAnotherKeyIsRefused() throws Exception {
    Device d = Device.create();
    Device other = Device.create();
    String body = bindBody(d, otpFor(d));
    Signed byOther = sign(other, "POST", "/app/v1/devices", body);
    Signed forged = new Signed(d.id(), byOther.timestamp(), byOther.nonce(), byOther.signature());
    assertCode(post("/app/v1/devices", body, forged, null), 401, "MW-AUTH-004");
  }

  // --- the binding OTP (review A5) ---

  @Test
  void theOtpGoesBySmsToTheTokensWalletWithOtpPriority() throws Exception {
    Device d = Device.create();
    MvcTestResult r = askOtp(d.id());
    assertThat(r).hasStatus(202);
    assertThat(r).bodyJson().extractingPath("$.wallet").isEqualTo(msisdn.substring(0, 4) + "****" + msisdn.substring(8));
    assertThat(r).bodyJson().extractingPath("$.sendsLeft").isEqualTo(4);

    NotificationRequest sms = lastSms();
    assertThat(sms.recipient().msisdn()).isEqualTo(msisdn);
    assertThat(sms.templateCode()).isEqualTo("DEVICE_BIND_OTP");
    assertThat(sms.priority()).isEqualTo("OTP");
    assertThat(sms.channels()).containsExactly("SMS");
    assertThat(sms.locale()).isEqualTo("en");
    assertThat(sms.params().get("otp")).matches("[0-9]{6}");
    assertThat(jdbc.queryForObject("select count(*) from outbox where payload::text like ?", Integer.class,
        "%" + sms.params().get("otp") + "%")).as("the OTP is never stored in clear").isZero();
  }

  @Test
  void anOtpRequestNeedsAValidDeviceId() {
    assertCode(askOtp(null), 401, "MW-AUTH-003");
    assertCode(askOtp("bad id!"), 401, "MW-AUTH-003");
  }

  @Test
  void aBindingWithoutAnOtpIsRefused() throws Exception {
    Device d = Device.create();
    String body = "{\"publicKey\":\"" + d.publicKey() + "\"}";
    MvcTestResult r = post("/app/v1/devices", body, sign(d, "POST", "/app/v1/devices", body), null);
    assertCode(r, 400, "MW-VAL-001");
    assertThat(r).bodyJson().extractingPath("$.details[0].field").isEqualTo("otp");
  }

  @Test
  void aBindingWhenNoOtpWasAskedForIsRefused() throws Exception {
    Device d = Device.create();
    assertCode(bindWith(d, "123456"), 401, "MW-AUTH-011");
    assertCode(status(d.id()), 401, "MW-AUTH-003");
  }

  @Test
  void aWrongOtpBindsNothingAndTheRightOneThenDoes() throws Exception {
    Device d = Device.create();
    String otp = otpFor(d);
    assertCode(bindWith(d, wrong(otp)), 401, "MW-AUTH-010");
    assertCode(status(d.id()), 401, "MW-AUTH-003");
    assertThat(bindWith(d, otp)).hasStatus(201);
  }

  @Test
  void anOtpIsSpentByTheBinding() throws Exception {
    Device d = Device.create();
    String otp = otpFor(d);
    assertThat(bindWith(d, otp)).hasStatus(201);
    assertCode(bindWith(d, otp), 401, "MW-AUTH-011");
  }

  @Test
  void anOtpAskedForOneDeviceDoesNotBindAnother() throws Exception {
    Device asked = Device.create();
    Device other = Device.create();
    String otp = otpFor(asked);
    assertCode(bindWith(other, otp), 401, "MW-AUTH-011");
    assertThat(bindWith(asked, otp)).as("its checks were not used").hasStatus(201);
  }

  @Test
  void threeWrongOtpsLockItAndTheRightOneIsThenRefused() throws Exception {
    Device d = Device.create();
    String otp = otpFor(d);
    for (int i = 0; i < 3; i++) {
      assertCode(bindWith(d, wrong(otp)), 401, "MW-AUTH-010");
    }
    assertCode(bindWith(d, otp), 401, "MW-AUTH-012");
    assertThat(bindWith(d, otpFor(d))).as("a new OTP").hasStatus(201);
  }

  @Test
  void parallelWrongGuessesGetNoMoreThanThreeChecks() throws Exception {
    Device d = Device.create();
    String otp = otpFor(d);
    int threads = 20;
    CountDownLatch start = new CountDownLatch(1);
    List<String> codes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    try (var pool = Executors.newFixedThreadPool(threads)) {
      List<Future<?>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < threads; i++) {
        String body = bindBody(d, wrong(otp));
        Signed signed = sign(d, "POST", "/app/v1/devices", body);
        futures.add(pool.submit(() -> {
          start.await();
          MvcTestResult r = post("/app/v1/devices", body, signed, null);
          codes.add(com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.code"));
          return null;
        }));
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    }
    assertThat(codes).filteredOn("MW-AUTH-010"::equals).as("guesses compared").hasSize(3);
    assertThat(codes).filteredOn("MW-AUTH-012"::equals).hasSize(threads - 3);
    assertCode(bindWith(d, otp), 401, "MW-AUTH-012");
  }

  @Test
  void otpRequestsAreLimitedPerWallet() throws Exception {
    Device d = Device.create();
    for (int i = 0; i < 5; i++) {
      assertThat(askOtp(d.id())).hasStatus(202);
    }
    assertCode(askOtp(d.id()), 429, "MW-VAL-022");
  }

  @Test
  void aBindingSendsTheCustomerANewDeviceSmsThroughTheOutbox() throws Exception {
    Device d = Device.create();
    assertThat(bind(d)).hasStatus(201);
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select msg_key, payload::text as payload from outbox where topic = 'mw.notify.request.v1'"
            + " and payload->>'templateCode' = 'DEVICE_BOUND' and payload->'recipient'->>'msisdn' = ?",
        msisdn);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("msg_key")).isEqualTo(msisdn);
    assertThat((String) rows.getFirst().get("payload")).contains("\"priority\": \"TXN\"").contains("\"locale\": \"en\"");
  }

  @Test
  void aRefusedBindingSendsNoNewDeviceSms() throws Exception {
    Device d = Device.create();
    String otp = otpFor(d);
    bindWith(d, wrong(otp));
    assertThat(jdbc.queryForObject(
        "select count(*) from outbox where payload->>'templateCode' = 'DEVICE_BOUND'"
            + " and payload->'recipient'->>'msisdn' = ?", Integer.class, msisdn)).isZero();
  }
}
