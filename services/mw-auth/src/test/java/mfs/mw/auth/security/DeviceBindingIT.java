package mfs.mw.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
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
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.ValkeyInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.NotificationRequest;
import mfs.mw.auth.messaging.EventPublisher;
import mfs.mw.auth.service.RequestSignatures;
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
 * Device binding, the binding OTP, signed requests, the nonce cache and sessions against real
 * PostgreSQL ({@code auth.device}, {@code auth.device_otp}) and Valkey (spec 9.1, 18.6, 18.7; the
 * cases of mw-app-gateway's ticket 29 / review A5 suite, moved with the code in ticket 68), plus the
 * internal lookups the gateway's verifier asks for ({@code /internal/v1/devices/{id}}, {@code
 * /internal/v1/sessions/{walletId}/{deviceId}}, with a services token of mw-app-gateway). The Kafka
 * publisher is a mock that hands the test the OTP SMS, as the SMSC would hand it to the customer.
 * Since ticket 69 no bearer token: the wallet is named by its MSISDN (mw-crypto's tokenisation is a
 * mock: {@code tok-<msisdn>}); a session is opened by a login, here the first-PIN OTP login (the
 * test wallets have no PIN). {@code LoginIT} covers the PIN login and the tokens.
 */
@SpringBootTest(
    properties = {
      "mw.outbox.relay.enabled=false",
      // the parallel tests load the shared Testcontainers Valkey; the shipped 500 ms is for production
      "spring.data.redis.timeout=3s"
    })
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ValkeyInfrastructure.class})
class DeviceBindingIT {

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate valkey;

  @MockitoBean EventPublisher publisher;
  @MockitoBean CryptoClient crypto;

  private String msisdn;

  @Autowired
  DeviceBindingIT(MockMvcTester mvc, JdbcTemplate jdbc, StringRedisTemplate valkey) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.valkey = valkey;
  }

  /** A wallet of its own per test (in the projection), so tests never see each other's devices. */
  @BeforeEach
  void freshWallet() {
    msisdn = newWallet();
    when(crypto.tokeniseMsisdn(anyString()))
        .thenAnswer(inv -> new CryptoClient.MsisdnToken("tok-" + inv.getArgument(0), "tokenise-msisdn.v1"));
    when(crypto.signJws(anyString(), anyString(), any(), any()))
        .thenReturn(new CryptoJwsSigned("eyJh.eyJp.c2ln", "jwt-sign.v1", "RS256"));
  }

  String newWallet() {
    String w = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    jdbc.update("delete from device where wallet_id = ?", w);
    jdbc.update("delete from device_otp where wallet_id = ?", w);
    jdbc.update("delete from wallet where wallet_id = ?", w);
    jdbc.update(
        "insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
            + " values (?, ?, 'tokenise-msisdn.v1', '{}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)",
        w, "tok-" + w);
    return w;
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
    return signAt(device, method, path, body, Instant.now());
  }

  static Signed signAt(Device device, String method, String path, String body, Instant at) throws Exception {
    String ts = Long.toString(at.toEpochMilli());
    String nonce = "n-" + UUID.randomUUID();
    String canonical =
        String.join("\n", method, path, RequestSignatures.sha256Hex(body.getBytes(StandardCharsets.UTF_8)), ts, nonce,
            device.id());
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(device.key().getPrivate());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    return new Signed(device.id(), ts, nonce, Base64.getEncoder().encodeToString(s.sign()));
  }

  /** Some JWT that is not a services token of an allowed client. */
  static RequestPostProcessor token(String wallet) {
    return jwt()
        .jwt(j -> j.subject("sub-" + wallet).claim("msisdn", wallet)
            .claim("realm_access", Map.of("roles", List.of("customer"))));
  }

  MvcTestResult post(String path, String body, Signed signed) {
    var req = mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body);
    if (signed != null) {
      req = req.header("X-Device-Id", signed.deviceId())
          .header("X-Timestamp", signed.timestamp())
          .header("X-Nonce", signed.nonce())
          .header("X-Signature", signed.signature());
    }
    return req.exchange();
  }

  MvcTestResult askOtp(String deviceId) {
    return askOtp(deviceId, "BIND");
  }

  MvcTestResult askOtp(String deviceId, String purpose) {
    var req = mvc.post().uri("/auth/v1/otp").contentType(MediaType.APPLICATION_JSON)
        .content("{\"msisdn\":\"" + msisdn + "\",\"purpose\":\"" + purpose + "\",\"locale\":\"en\"}");
    if (deviceId != null) {
      req = req.header("X-Device-Id", deviceId);
    }
    return req.exchange();
  }

  /** Asks for a binding OTP for {@code device} and returns the code the SMS carried. */
  String otpFor(Device device) {
    return otpFor(device, "BIND");
  }

  String otpFor(Device device, String purpose) {
    clearInvocations(publisher);
    assertThat(askOtp(device.id(), purpose)).hasStatus(202);
    return lastSms().params().get("otp");
  }

  NotificationRequest lastSms() {
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(publisher, atLeastOnce()).publish(eq("mw.notify.request.v1"), eq(msisdn), any(), sent.capture());
    return (NotificationRequest) sent.getValue();
  }

  String bindBody(Device device, String otp) {
    return "{\"msisdn\":\"" + msisdn + "\",\"publicKey\":\"" + device.publicKey() + "\",\"otp\":\"" + otp + "\"}";
  }

  MvcTestResult bindWith(Device device, String otp) throws Exception {
    String body = bindBody(device, otp);
    return post("/auth/v1/devices", body, sign(device, "POST", "/auth/v1/devices", body));
  }

  MvcTestResult bind(Device device) throws Exception {
    return bindWith(device, otpFor(device));
  }

  /** Opens a session: the first-PIN login with a LOGIN OTP (the test wallets have no PIN). */
  MvcTestResult openSession(Device device) throws Exception {
    String body = "{\"msisdn\":\"" + msisdn + "\",\"otp\":\"" + otpFor(device, "LOGIN") + "\"}";
    return post("/auth/v1/sessions", body, sign(device, "POST", "/auth/v1/sessions", body));
  }

  static String wrong(String otp) {
    return otp.equals("000000") ? "999999" : "000000";
  }

  /** The gateway's lookups, with its services token (mTLS is off in the test JVM). */
  MvcTestResult internal(String path) {
    return mvc.get().uri(path).header("Authorization", InternalTokens.bearer("mw-app-gateway")).exchange();
  }

  MvcTestResult device(Device d) {
    return internal("/internal/v1/devices/" + d.id());
  }

  MvcTestResult session(Device d) {
    return internal("/internal/v1/sessions/" + msisdn + "/" + d.id());
  }

  static void assertCode(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  @Test
  void aBoundDeviceIsSeenByTheGatewayWithItsWalletKeyAndLiveSession() throws Exception {
    Device d = Device.create();
    MvcTestResult bound = bind(d);
    assertThat(bound).hasStatus(201);
    assertThat(bound).bodyJson().extractingPath("$.deviceId").isEqualTo(d.id());
    assertThat(bound).bodyJson().extractingPath("$.wallet").isEqualTo(msisdn.substring(0, 4) + "****" + msisdn.substring(8));
    assertCode(session(d), 404, "MW-AUTH-007"); // a binding opens no session: log in next
    assertThat(openSession(d)).hasStatus(200);

    MvcTestResult key = device(d);
    assertThat(key).hasStatus(200);
    assertThat(key).bodyJson().extractingPath("$.walletId").isEqualTo(msisdn);
    assertThat(key).bodyJson().extractingPath("$.publicKey").isEqualTo(d.publicKey());

    MvcTestResult live = session(d);
    assertThat(live).hasStatus(200);
    assertThat(live).bodyJson().extractingPath("$.idleTimeoutSeconds").isEqualTo(300);
    assertThat(live).bodyJson().extractingPath("$.deviceKeyFingerprint").isEqualTo(
        mfs.mw.auth.service.DeviceBindingService.fingerprint(d.publicKey()));
    assertThat(live).bodyJson().extractingPath("$.boundAt").isNotNull();
    assertThat(valkey.getExpire("sess:" + msisdn + ":" + d.id())).isPositive();
  }

  @Test
  void aKeyReplacedByARebindingChangesTheSessionsFingerprint() throws Exception {
    Device d = Device.create();
    bind(d);
    openSession(d);
    String before = com.jayway.jsonpath.JsonPath.read(session(d).getResponse().getContentAsString(),
        "$.deviceKeyFingerprint");
    Device sameIdNewKey = new Device(d.id(), Device.create().key());
    assertThat(bind(sameIdNewKey)).hasStatus(201);
    assertCode(session(d), 404, "MW-AUTH-007"); // the rebinding ended the session
    openSession(sameIdNewKey);
    String after = com.jayway.jsonpath.JsonPath.read(session(d).getResponse().getContentAsString(),
        "$.deviceKeyFingerprint");
    assertThat(after).isNotEqualTo(before)
        .isEqualTo(mfs.mw.auth.service.DeviceBindingService.fingerprint(sameIdNewKey.publicKey()));
  }

  @Test
  void theInternalLookupsNeedAServiceToken() throws Exception {
    Device d = Device.create();
    bind(d);
    assertCode(mvc.get().uri("/internal/v1/devices/" + d.id()).exchange(), 401, "MW-AUTH-001");
    assertCode(mvc.get().uri("/internal/v1/devices/" + d.id()).with(token(msisdn)).exchange(), 403, "MW-AUTH-002");
  }

  @Test
  void theSeedApiIsOffUnlessSwitchedOn() {
    assertCode(mvc.post().uri("/internal/v1/seed/pins").contentType(MediaType.APPLICATION_JSON)
        .content("{\"pins\":[]}").exchange(), 401, "MW-AUTH-001");
  }

  @Test
  void anOtpForAnotherPurposeDoesNotBind() throws Exception {
    Device d = Device.create();
    String login = otpFor(d, "LOGIN");
    assertCode(bindWith(d, login), 401, "MW-AUTH-011");
  }

  @Test
  void aLoginOtpIsSentWithItsOwnTemplate() throws Exception {
    Device d = Device.create();
    otpFor(d, "LOGIN");
    assertThat(lastSms().templateCode()).isEqualTo("LOGIN_OTP");
  }

  @Test
  void aWalletMwAuthDoesNotKnowIsRefused() throws Exception {
    jdbc.update("delete from wallet where wallet_id = ?", msisdn);
    assertCode(askOtp(Device.create().id()), 403, "MW-AUTH-002");
  }

  @Test
  void aBindingRequestCannotBeReplayed() throws Exception {
    Device d = Device.create();
    String body = bindBody(d, otpFor(d));
    Signed signed = sign(d, "POST", "/auth/v1/devices", body);
    assertThat(post("/auth/v1/devices", body, signed)).hasStatus(201);
    assertCode(post("/auth/v1/devices", body, signed), 401, "MW-AUTH-006");
  }

  @Test
  void aTamperedBindingBodyIsRejected() throws Exception {
    Device d = Device.create();
    String otp = otpFor(d);
    Signed signed = sign(d, "POST", "/auth/v1/devices", bindBody(d, otp));
    assertCode(post("/auth/v1/devices", bindBody(d, wrong(otp)), signed), 401, "MW-AUTH-004");
  }

  @Test
  void aStaleTimestampIsRejected() throws Exception {
    Device d = Device.create();
    String body = bindBody(d, otpFor(d));
    Signed old = signAt(d, "POST", "/auth/v1/devices", body, Instant.now().minusSeconds(600));
    assertCode(post("/auth/v1/devices", body, old), 401, "MW-AUTH-005");
  }

  @Test
  void anUnboundDeviceHasNoKeyNoSessionAndCannotOpenOne() throws Exception {
    Device d = Device.create();
    assertCode(device(d), 404, "MW-AUTH-003");
    assertCode(session(d), 404, "MW-AUTH-003");
    assertCode(openSession(d), 401, "MW-AUTH-047");
    assertCode(internal("/internal/v1/devices/short"), 401, "MW-AUTH-003");
  }

  @Test
  void bindingANewDeviceUnbindsTheOldOneAndEndsItsSession() throws Exception {
    Device first = Device.create();
    Device second = Device.create();
    bind(first);
    assertThat(openSession(first)).hasStatus(200);
    bind(second);
    assertCode(device(first), 404, "MW-AUTH-003");
    assertCode(session(first), 404, "MW-AUTH-003");
    assertCode(openSession(first), 401, "MW-AUTH-047");
    assertThat(device(second)).hasStatus(200);
    assertThat(openSession(second)).hasStatus(200);
    assertThat(session(second)).hasStatus(200);
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where wallet_id = ? and not revoked",
        Integer.class, msisdn)).as("the first device's session was revoked").isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from device where wallet_id = ? and unbound_at is null",
        Integer.class, msisdn)).isEqualTo(1);
  }

  @Test
  void aDeviceBoundByAnotherWalletMovesThereAndItsSessionHereEnds() throws Exception {
    Device d = Device.create();
    bind(d);
    openSession(d);
    String mine = msisdn;
    msisdn = newWallet();
    assertThat(bind(d)).hasStatus(201);

    MvcTestResult key = device(d);
    assertThat(key).bodyJson().extractingPath("$.walletId").isEqualTo(msisdn);
    assertCode(internal("/internal/v1/sessions/" + mine + "/" + d.id()), 404, "MW-AUTH-003");
    assertThat(valkey.hasKey("sess:" + mine + ":" + d.id())).isFalse();
  }

  @Test
  void anExpiredSessionIsGoneUntilTheNextLogin() throws Exception {
    Device d = Device.create();
    bind(d);
    openSession(d);
    valkey.delete("sess:" + msisdn + ":" + d.id()); // what the idle timeout does
    assertCode(session(d), 404, "MW-AUTH-007");

    MvcTestResult opened = openSession(d);
    assertThat(opened).hasStatus(200);
    assertThat(opened).bodyJson().extractingPath("$.idleTimeoutSeconds").isEqualTo(300);
    assertThat(session(d)).hasStatus(200);
  }

  @Test
  void aKeyThatIsNotP256IsRefusedAtBinding() throws Exception {
    Device d = Device.create();
    String body = "{\"msisdn\":\"" + msisdn + "\",\"publicKey\":\"bm90IGEga2V5\",\"otp\":\"" + otpFor(d) + "\"}";
    assertCode(post("/auth/v1/devices", body, sign(d, "POST", "/auth/v1/devices", body)), 400, "MW-VAL-001");
  }

  @Test
  void aBindingSignedByAnotherKeyIsRefused() throws Exception {
    Device d = Device.create();
    Device other = Device.create();
    String body = bindBody(d, otpFor(d));
    Signed byOther = sign(other, "POST", "/auth/v1/devices", body);
    Signed forged = new Signed(d.id(), byOther.timestamp(), byOther.nonce(), byOther.signature());
    assertCode(post("/auth/v1/devices", body, forged), 401, "MW-AUTH-004");
  }

  // --- the binding OTP (review A5) ---

  @Test
  void theOtpGoesBySmsToTheWalletWithOtpPriority() throws Exception {
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
    String body = "{\"msisdn\":\"" + msisdn + "\",\"publicKey\":\"" + d.publicKey() + "\"}";
    MvcTestResult r = post("/auth/v1/devices", body, sign(d, "POST", "/auth/v1/devices", body));
    assertCode(r, 400, "MW-VAL-001");
    assertThat(r).bodyJson().extractingPath("$.details[0].field").isEqualTo("otp");
  }

  @Test
  void aBindingWhenNoOtpWasAskedForIsRefused() throws Exception {
    Device d = Device.create();
    assertCode(bindWith(d, "123456"), 401, "MW-AUTH-011");
    assertCode(device(d), 404, "MW-AUTH-003");
  }

  @Test
  void aWrongOtpBindsNothingAndTheRightOneThenDoes() throws Exception {
    Device d = Device.create();
    String otp = otpFor(d);
    assertCode(bindWith(d, wrong(otp)), 401, "MW-AUTH-010");
    assertCode(device(d), 404, "MW-AUTH-003");
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
        Signed signed = sign(d, "POST", "/auth/v1/devices", body);
        futures.add(pool.submit(() -> {
          start.await();
          MvcTestResult r = post("/auth/v1/devices", body, signed);
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
