package mfs.mw.auth.security;

import static mfs.mw.auth.security.LoginIT.assertCode;
import static mfs.mw.auth.security.LoginIT.clear;
import static mfs.mw.auth.security.LoginIT.field;
import static mfs.mw.auth.security.LoginIT.hmac;
import static mfs.mw.auth.security.LoginIT.pinJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.ValkeyInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.contracts.ContractSchemas;
import mfs.mw.auth.dto.CryptoPinDerived;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.dto.CryptoTokens;
import mfs.mw.auth.dto.NotificationRequest;
import mfs.mw.auth.messaging.CoreWalletEventListener;
import mfs.mw.auth.messaging.EventPublisher;
import mfs.mw.auth.security.DeviceBindingIT.Device;
import mfs.mw.auth.security.DeviceBindingIT.Signed;
import org.apache.camel.CamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeAll;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The PIN lifecycle of ticket 70 against real PostgreSQL and Valkey (mw-crypto a behaving mock, as in
 * {@link LoginIT}): the lockout levels through login and device binding (the lock checked before
 * mw-crypto is asked), the decay of the level history, PIN change with step-up and the reuse rule,
 * the self-service reset with the core's answer, and the care reset of mw-admin-api.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "spring.data.redis.timeout=3s"})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ValkeyInfrastructure.class})
class PinLifecycleIT {

  static final String PIN = "13579";
  static final String WRONG = "24680";

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate valkey;
  private final CoreWalletEventListener coreEvents;
  private final CamelContext camel;
  private final JsonMapper json;

  @MockitoBean EventPublisher publisher;
  @MockitoBean CryptoClient crypto;

  String msisdn;
  Device device;

  @Autowired
  PinLifecycleIT(MockMvcTester mvc, JdbcTemplate jdbc, StringRedisTemplate valkey, CoreWalletEventListener coreEvents,
      CamelContext camel, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.valkey = valkey;
    this.coreEvents = coreEvents;
    this.camel = camel;
    this.json = json;
  }

  @BeforeAll
  static void keys() throws Exception {
    LoginIT.keys();
  }

  @BeforeEach
  void setUp() throws Exception {
    when(crypto.tokeniseMsisdn(anyString()))
        .thenAnswer(inv -> new CryptoClient.MsisdnToken("tok-" + inv.getArgument(0), "tokenise-msisdn.v1"));
    when(crypto.verifyPin(anyString(), any(), anyString(), anyString())).thenAnswer(inv -> new CryptoPinVerified(
        hmac(inv.getArgument(0), clear(inv.getArgument(1))).equals(inv.getArgument(2)), null));
    when(crypto.derivePin(anyString(), any(), any())).thenAnswer(inv -> {
      String pin = clear(inv.getArgument(1));
      return pin.chars().distinct().count() == 1
          ? new CryptoPinDerived(true, "REPEATED_DIGIT", null, null)
          : new CryptoPinDerived(false, null, hmac(inv.getArgument(0), pin), "pin-hmac.v1");
    });
    when(crypto.signJws(eq("JWT_SIGN"), eq("RS256"), any(), any())).thenAnswer(inv -> {
      Map<String, Object> claims = inv.getArgument(3);
      var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
          com.nimbusds.jose.JWSAlgorithm.RS256).type(new com.nimbusds.jose.JOSEObjectType("at+jwt"))
          .keyID(LoginIT.jwtKey.getKeyID()).build(), com.nimbusds.jwt.JWTClaimsSet.parse(claims));
      jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(LoginIT.jwtKey));
      return new mfs.mw.auth.dto.CryptoJwsSigned(jwt.serialize(), LoginIT.jwtKey.getKeyID(), "RS256");
    });
    when(crypto.jwks("JWT_SIGN"))
        .thenAnswer(inv -> new com.nimbusds.jose.jwk.JWKSet(LoginIT.jwtKey.toPublicJWK()).toJSONObject());
    when(crypto.decrypt(any())).thenAnswer(inv -> msisdn.getBytes(StandardCharsets.UTF_8));
    when(crypto.tokeniseNid(anyList())).thenAnswer(inv -> {
      List<String> values = inv.getArgument(0);
      return new CryptoTokens("NID", "tokenise-nid.v1",
          values.stream().map(v -> "NID_" + Integer.toUnsignedString(v.hashCode(), 32).toUpperCase()
              .replace('0', 'A').replace('1', 'B').replace('8', 'C').replace('9', 'D')).toList());
    });

    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'tokenise-msisdn.v1', '{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AA==\","
        + "\"ciphertext\":\"Yw==\",\"aad\":\"aWQ=\"}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", msisdn, "tok-" + msisdn);
    jdbc.update("insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values (?, ?, 'pin-hmac.v1', now())",
        msisdn, hmac(msisdn, PIN));
    device = Device.create();
    jdbc.update("insert into device (device_id, wallet_id, public_key, bound_at) values (?, ?, ?, ?)",
        device.id(), msisdn, device.publicKey(), Timestamp.from(Instant.now()));
  }

  // --- helpers ---

  MvcTestResult signed(Device d, String method, String path, String body) throws Exception {
    Signed s = DeviceBindingIT.sign(d, method, path, body);
    var req = "PUT".equals(method) ? mvc.put().uri(path) : mvc.post().uri(path);
    return req.contentType(MediaType.APPLICATION_JSON).content(body)
        .header("X-Device-Id", s.deviceId()).header("X-Timestamp", s.timestamp()).header("X-Nonce", s.nonce())
        .header("X-Signature", s.signature()).exchange();
  }

  MvcTestResult login(String pin) throws Exception {
    return signed(device, "POST", "/auth/v1/sessions",
        "{\"msisdn\":\"" + msisdn + "\",\"encryptedPin\":" + pinJson(pin) + "}");
  }

  String otp(Device d, String purpose) throws Exception {
    clearInvocations(publisher);
    var ask = mvc.post().uri("/auth/v1/otp").header("X-Device-Id", d.id()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"msisdn\":\"" + msisdn + "\",\"purpose\":\"" + purpose + "\"}").exchange();
    assertThat(ask).hasStatus(202);
    ArgumentCaptor<Object> sms = ArgumentCaptor.forClass(Object.class);
    verify(publisher, atLeastOnce()).publish(eq("mw.notify.request.v1"), eq(msisdn), any(), sms.capture());
    return ((NotificationRequest) sms.getValue()).params().get("otp");
  }

  MvcTestResult bind(Device d, String otp, String pin) throws Exception {
    return signed(d, "POST", "/auth/v1/devices", "{\"msisdn\":\"" + msisdn + "\",\"publicKey\":\"" + d.publicKey()
        + "\",\"otp\":\"" + otp + "\",\"encryptedPin\":" + pinJson(pin) + "}");
  }

  Map<String, Object> pinLock() {
    return jdbc.queryForMap("select level, until from lock where wallet_id = ? and kind = 'PIN'", msisdn);
  }

  void lockRunsOut() {
    jdbc.update("update lock set until = now() - interval '1 second' where wallet_id = ? and kind = 'PIN'", msisdn);
  }

  long retryAfter(MvcTestResult r) throws Exception {
    assertThat(field(r, "$.details[0].field")).isEqualTo("retryAfter");
    return Long.parseLong(field(r, "$.details[0].issue"));
  }

  List<JsonNode> outbox(String topic) {
    return jdbc.queryForList("select payload::text from outbox where topic = ? and msg_key like ? order by id",
            String.class, topic, "%")
        .stream().map(json::readTree).toList();
  }

  List<JsonNode> audits(String action) {
    return outbox("mw.audit.event.v1").stream()
        .filter(a -> a.path("action").asString("").equals(action)
            && a.path("resource").asString("").equals(mfs.mw.auth.mapper.AuditEventMapper.resource(msisdn)))
        .toList();
  }

  // --- lockout ---

  @Test
  void loginLocksTheePinLevelByLevelAndALockedPinNeverReachesMwCrypto() throws Exception {
    MvcTestResult first = login(WRONG);
    assertCode(first, 401, "MW-AUTH-040");
    assertThat(field(first, "$.details[0].field")).isEqualTo("triesLeft");
    assertThat(field(first, "$.details[0].issue")).isEqualTo("2");
    assertThat(field(login(WRONG), "$.details[0].issue")).isEqualTo("1");
    MvcTestResult third = login(WRONG);
    assertCode(third, 423, "MW-AUTH-041");
    assertThat(retryAfter(third)).isBetween(3500L, 3600L);
    assertThat(pinLock()).containsEntry("level", 1);
    assertThat(audits("auth.pin.locked")).hasSize(1);
    assertThat(audits("auth.pin.locked").getFirst().path("after").path("level").asInt()).isEqualTo(1);
    assertThat(audits("auth.pin.locked").getFirst().toString()).doesNotContain(msisdn);

    clearInvocations(crypto);
    assertCode(login(PIN), 423, "MW-AUTH-041");
    verify(crypto, never()).verifyPin(anyString(), any(), anyString(), anyString());

    lockRunsOut();
    assertThat(login(PIN)).as("level 1 over").hasStatus(200);
    assertThat(pinLock()).as("the level history stays").containsEntry("level", 1);
    login(WRONG);
    login(WRONG);
    MvcTestResult second = login(WRONG);
    assertCode(second, 423, "MW-AUTH-041");
    assertThat(retryAfter(second)).isBetween(86_300L, 86_400L);
    assertThat(pinLock()).containsEntry("level", 2);

    lockRunsOut();
    login(WRONG);
    login(WRONG);
    assertCode(login(WRONG), 423, "MW-AUTH-042");
    assertThat(pinLock()).containsEntry("level", 3).containsEntry("until", null);
    clearInvocations(crypto);
    assertCode(login(PIN), 423, "MW-AUTH-042");
    verify(crypto, never()).verifyPin(anyString(), any(), anyString(), anyString());
    assertThat(audits("auth.pin.locked")).hasSize(3);
  }

  @Test
  void theLevelHistoryDecaysAfterThirtyDays() throws Exception {
    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor, created_at)"
        + " values (?, 'PIN', 2, now() - interval '30 days', 'WRONG_PIN', 'mw-auth', now() - interval '31 days')", msisdn);
    login(WRONG);
    login(WRONG);
    assertCode(login(WRONG), 423, "MW-AUTH-041");
    assertThat(pinLock()).containsEntry("level", 1);
  }

  @Test
  void deviceBindingCountsWrongPinsLocksAndThenRefusesBeforeMwCrypto() throws Exception {
    Device next = Device.create();
    String code = otp(next, "BIND");
    assertCode(bind(next, code, WRONG), 401, "MW-AUTH-040");
    assertCode(bind(next, code, WRONG), 401, "MW-AUTH-040");
    assertCode(bind(next, code, WRONG), 423, "MW-AUTH-041");
    assertThat(pinLock()).containsEntry("level", 1);

    clearInvocations(crypto);
    assertCode(bind(next, otp(next, "BIND"), PIN), 423, "MW-AUTH-041");
    verify(crypto, never()).verifyPin(anyString(), any(), anyString(), anyString());
    assertCode(login(PIN), 423, "MW-AUTH-041");
    assertThat(jdbc.queryForObject("select count(*) from device where device_id = ?", Integer.class, next.id())).isZero();
  }

  MvcTestResult authorise(String pin) {
    String body = "{\"walletId\":\"" + msisdn + "\",\"channel\":\"USSD\",\"encryptedPin\":" + pinJson(pin)
        + ",\"txn\":{\"txnType\":\"P2P\",\"amount\":1000,\"currency\":\"BDT\",\"payer\":{\"type\":\"WALLET\",\"id\":\""
        + msisdn + "\"},\"payee\":{\"type\":\"WALLET\",\"id\":\"01811000002\"},\"idempotencyKey\":\""
        + UUID.randomUUID() + "\"}}";
    return mvc.post().uri("/internal/v1/txn-authorisations").contentType(MediaType.APPLICATION_JSON).content(body)
        .header("Authorization", InternalTokens.bearer("mw-ussd")).exchange();
  }

  @Test
  void theTransactionAssertionCountsWrongPinsLocksAndThenRefusesBeforeMwCrypto() throws Exception {
    assertCode(authorise(WRONG), 401, "MW-AUTH-040");
    assertCode(login(WRONG), 401, "MW-AUTH-040");
    MvcTestResult locking = authorise(WRONG);
    assertCode(locking, 423, "MW-AUTH-041");
    assertThat(pinLock()).as("login and assertion share the count").containsEntry("level", 1);

    clearInvocations(crypto);
    assertCode(authorise(PIN), 423, "MW-AUTH-041");
    verify(crypto, never()).verifyPin(anyString(), any(), anyString(), anyString());
    verify(crypto, never()).signJws(any(), any(), any(), any());

    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor) values (?, 'ADMIN', 1, null, 'it', 'it')",
        msisdn);
    lockRunsOut();
    assertCode(authorise(PIN), 403, "MW-AUTH-043");
    verify(crypto, never()).verifyPin(anyString(), any(), anyString(), anyString());
  }

  @Test
  void parallelWrongPinsNeverSkipALevel() throws Exception {
    List<Thread> threads = new java.util.ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Thread t = Thread.ofVirtual().start(() -> {
        try {
          login(WRONG);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      });
      threads.add(t);
    }
    for (Thread t : threads) {
      t.join();
    }
    assertThat(pinLock()).as("the wrong PINs after the lock are not counted").containsEntry("level", 1);
  }

  // --- change ---

  @Test
  @SuppressWarnings("unchecked")
  void aPinChangeNeedsTheCurrentPinChecksThePolicyAndRefusesTheLastPins() throws Exception {
    String bearer = "Bearer " + field(login(PIN), "$.accessToken");
    String wrong = "{\"encryptedPin\":" + pinJson(WRONG) + ",\"newEncryptedPin\":" + pinJson("97531") + "}";
    assertCode(mvc.put().uri("/auth/v1/pin").contentType(MediaType.APPLICATION_JSON).content(wrong).exchange(), 401,
        "MW-AUTH-001");
    assertCode(mvc.put().uri("/auth/v1/pin").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + pinJson(PIN) + "}").exchange(), 400, "MW-VAL-001");
    MvcTestResult refused = mvc.put().uri("/auth/v1/pin").header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON).content(wrong).exchange();
    assertCode(refused, 401, "MW-AUTH-040");
    assertThat(jdbc.queryForObject("select count from attempt where wallet_id = ?", Integer.class, msisdn)).isEqualTo(1);

    MvcTestResult weak = mvc.put().uri("/auth/v1/pin").header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + pinJson(PIN) + ",\"newEncryptedPin\":" + pinJson("11111") + "}").exchange();
    assertCode(weak, 400, "MW-AUTH-044");

    ArgumentCaptor<Map<String, Object>> policy = ArgumentCaptor.forClass(Map.class);
    MvcTestResult changed = mvc.put().uri("/auth/v1/pin").header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + pinJson(PIN) + ",\"newEncryptedPin\":" + pinJson("97531") + "}").exchange();
    assertThat(changed).hasStatus(200);
    verify(crypto, atLeastOnce()).derivePin(eq(msisdn), any(), policy.capture());
    assertThat(policy.getValue()).containsEntry("msisdn", msisdn).containsEntry("lastHmac", hmac(msisdn, PIN))
        .containsEntry("lastHmacKeyId", "pin-hmac.v1");
    assertThat(jdbc.queryForObject("select pin_hmac from credential where wallet_id = ?", String.class, msisdn))
        .isEqualTo(hmac(msisdn, "97531"));
    assertThat(audits("auth.pin.changed")).hasSize(1);
    assertThat(outbox("mw.notify.request.v1")).anySatisfy(n ->
        assertThat(n.path("templateCode").asString()).isEqualTo("PIN_CHANGED"));
    assertCode(mvc.put().uri("/auth/v1/pin").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + pinJson("97531") + ",\"newEncryptedPin\":" + pinJson("86420") + "}").exchange(),
        401, "MW-AUTH-001");

    String next = "Bearer " + field(changed, "$.accessToken");
    MvcTestResult back = mvc.put().uri("/auth/v1/pin").header("Authorization", next)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + pinJson("97531") + ",\"newEncryptedPin\":" + pinJson(PIN) + "}").exchange();
    assertCode(back, 400, "MW-AUTH-044");
    assertThat(field(back, "$.details[0].issue")).isEqualTo("LAST_PIN");
  }

  // --- self-service reset ---

  String startSelf(Device d, String code) throws Exception {
    MvcTestResult r = signed(d, "POST", "/auth/v1/pin-resets", "{\"msisdn\":\"" + msisdn + "\",\"otp\":\"" + code
        + "\",\"nidLast4\":\"1234\",\"dateOfBirth\":\"1990-01-31\"}");
    assertThat(r).hasStatus(202);
    assertThat(r).bodyJson().extractingPath("$.status").isEqualTo("PENDING_KYC");
    assertThat(r).bodyJson().extractingPath("$.kind").isEqualTo("SELF");
    return field(r, "$.resetId");
  }

  MvcTestResult status(Device d, String resetId) {
    return mvc.get().uri("/auth/v1/pin-resets/" + resetId).header("X-Device-Id", d.id()).exchange();
  }

  MvcTestResult setPin(Device d, String resetId, String pin) {
    return mvc.put().uri("/auth/v1/pin-resets/" + resetId + "/pin").header("X-Device-Id", d.id())
        .contentType(MediaType.APPLICATION_JSON).content("{\"encryptedPin\":" + pinJson(pin) + "}").exchange();
  }

  /** The core's answer as mw-core-mock sends it, delivered to the core event listener. */
  void coreAnswers(JsonNode command, boolean verified, String eventId) throws Exception {
    String body = json.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "eventType", verified ? "CustomerFactorsVerified" : "CustomerFactorsRejected",
        "eventId", eventId,
        "commandId", command.path("commandId").asString(),
        "txnId", command.path("txnId").asString(),
        "coreRef", "CORE-1",
        "occurredAt", Instant.now().toString(),
        "outcome", verified ? "SUCCESS" : "FAILURE",
        "reasonCode", verified ? "-" : "FACTORS_MISMATCH",
        "payload", Map.of("resetId", command.path("payload").path("resetId").asString(), "walletId", msisdn)));
    var exchange = new DefaultExchange(camel);
    exchange.getMessage().setBody(body);
    coreEvents.handle(exchange);
  }

  JsonNode kycCommand(String resetId) {
    return outbox("core.wallet.command.v1").stream()
        .filter(c -> c.path("payload").path("resetId").asString("").equals(resetId)).findFirst().orElseThrow();
  }

  @Test
  void aSelfServiceResetAsksTheCoreThenSetsThePinAndLiftsTheLock() throws Exception {
    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor) values (?, 'PIN', 3, null, 'WRONG_PIN', 'mw-auth')",
        msisdn);
    String refresh = "rt-" + UUID.randomUUID();
    jdbc.update("insert into refresh_token (token_hash, family, wallet_id, device_id, expires_at) values (?, ?, ?, ?, now() + interval '1 day')",
        refresh, UUID.randomUUID(), msisdn, device.id());

    String resetId = startSelf(device, otp(device, "RESET"));
    JsonNode cmd = kycCommand(resetId);
    assertThat(ContractSchemas.schema("events/mw-orchestrator/core.command.v1.schema.json").validate(cmd)).isEmpty();
    assertThat(cmd.path("commandType").asString()).isEqualTo("VerifyCustomerFactors");
    assertThat(cmd.path("commandId").asString()).isEqualTo("pin-reset:" + resetId);
    assertThat(cmd.toString()).as("the factors never go in clear").doesNotContain("1234").doesNotContain("1990-01-31");
    verify(crypto).tokeniseNid(List.of(msisdn + ":1234", msisdn + ":1990-01-31"));

    assertThat(status(device, resetId)).bodyJson().extractingPath("$.status").isEqualTo("PENDING_KYC");
    assertThat(status(Device.create(), resetId)).as("another device").hasStatus(404);
    assertCode(setPin(device, resetId, "97531"), 409, "MW-VAL-031");

    coreAnswers(cmd, true, UUID.randomUUID().toString());
    assertThat(status(device, resetId)).bodyJson().extractingPath("$.status").isEqualTo("VERIFIED");
    assertCode(setPin(device, resetId, "11111"), 400, "MW-AUTH-044");
    assertThat(status(device, resetId)).bodyJson().extractingPath("$.status").isEqualTo("VERIFIED");

    MvcTestResult done = setPin(device, resetId, "97531");
    assertThat(done).hasStatus(200);
    assertThat(done).bodyJson().extractingPath("$.status").isEqualTo("DONE");
    assertThat(jdbc.queryForObject("select count(*) from lock where wallet_id = ?", Integer.class, msisdn)).isZero();
    assertThat(jdbc.queryForObject("select revoked from refresh_token where token_hash = ?", Boolean.class, refresh))
        .isTrue();
    assertThat(audits("auth.pin_reset.done")).hasSize(1);
    assertThat(login("97531")).hasStatus(200);
    assertCode(setPin(device, resetId, "86420"), 409, "MW-VAL-031");
  }

  @Test
  void aRejectionOfTheCoreClosesTheResetAndADuplicateAnswerChangesNothing() throws Exception {
    String resetId = startSelf(device, otp(device, "RESET"));
    String eventId = UUID.randomUUID().toString();
    coreAnswers(kycCommand(resetId), false, eventId);
    MvcTestResult r = status(device, resetId);
    assertThat(r).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
    assertThat(r).bodyJson().extractingPath("$.reason").isEqualTo("FACTORS_MISMATCH");
    coreAnswers(kycCommand(resetId), true, eventId);
    assertThat(status(device, resetId)).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
    assertCode(setPin(device, resetId, "97531"), 409, "MW-VAL-031");
  }

  @Test
  void aSelfServiceResetNeedsTheBoundDeviceItsSignatureAndAResetOtp() throws Exception {
    Device other = Device.create();
    var ask = mvc.post().uri("/auth/v1/otp").header("X-Device-Id", other.id()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"msisdn\":\"" + msisdn + "\",\"purpose\":\"RESET\"}").exchange();
    assertCode(ask, 403, "MW-AUTH-002");
    String body = "{\"msisdn\":\"" + msisdn + "\",\"otp\":\"123456\",\"nidLast4\":\"1234\",\"dateOfBirth\":\"1990-01-31\"}";
    assertCode(signed(other, "POST", "/auth/v1/pin-resets", body), 401, "MW-AUTH-047");
    assertCode(mvc.post().uri("/auth/v1/pin-resets").header("X-Device-Id", device.id())
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange(), 401, "MW-AUTH-004");
    String login = otp(device, "BIND");
    body = "{\"msisdn\":\"" + msisdn + "\",\"otp\":\"" + login + "\",\"nidLast4\":\"1234\",\"dateOfBirth\":\"1990-01-31\"}";
    assertCode(signed(device, "POST", "/auth/v1/pin-resets", body), 401, "MW-AUTH-011");
    assertCode(signed(device, "POST", "/auth/v1/pin-resets", "{\"msisdn\":\"" + msisdn + "\",\"otp\":\"123456\","
        + "\"nidLast4\":\"1234\"}"), 400, "MW-VAL-001");
  }

  // --- care reset ---

  MvcTestResult care(String client, UUID changeRequestId) {
    return mvc.post().uri("/internal/v1/pin-resets")
        .header("Authorization", InternalTokens.bearer(client))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"walletId\":\"" + msisdn + "\",\"changeRequestId\":\"" + changeRequestId
            + "\",\"makerId\":\"ops-maker01\",\"checkerId\":\"ops-checker01\",\"reason\":\"lost phone\"}")
        .exchange();
  }

  @Test
  void aCareResetOfMwAdminApiLetsTheCustomerSetAPinOnANewDevice() throws Exception {
    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor) values (?, 'PIN', 3, null, 'WRONG_PIN', 'mw-auth')",
        msisdn);
    UUID cr = UUID.randomUUID();
    assertCode(care("mw-ussd", cr), 403, "MW-AUTH-002");
    MvcTestResult opened = care("mw-admin-api", cr);
    assertThat(opened).hasStatus(201);
    assertThat(opened).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
    MvcTestResult again = care("mw-admin-api", cr);
    assertThat(again).as("idempotent on the change request").hasStatus(200);
    assertThat(field(again, "$.resetId")).isEqualTo(field(opened, "$.resetId"));
    assertThat(outbox("mw.notify.request.v1")).anySatisfy(n ->
        assertThat(n.path("templateCode").asString()).isEqualTo("PIN_RESET_APPROVED"));
    assertThat(audits("auth.pin_reset.approved")).hasSize(1);

    Device lost = Device.create();
    String code = otp(lost, "RESET");
    assertCode(signed(device, "POST", "/auth/v1/pin-resets", "{\"msisdn\":\"" + msisdn + "\",\"otp\":\"123456\","
        + "\"nidLast4\":\"1234\",\"dateOfBirth\":\"1990-01-31\"}"), 409, "MW-VAL-030");
    MvcTestResult taken = mvc.post().uri("/auth/v1/pin-resets").header("X-Device-Id", lost.id())
        .contentType(MediaType.APPLICATION_JSON).content("{\"msisdn\":\"" + msisdn + "\",\"otp\":\"" + code + "\"}")
        .exchange();
    assertThat(taken).hasStatus(202);
    assertThat(taken).bodyJson().extractingPath("$.status").isEqualTo("VERIFIED");
    assertThat(taken).bodyJson().extractingPath("$.kind").isEqualTo("CARE");
    assertThat(status(device, field(taken, "$.resetId"))).as("the old device").hasStatus(404);

    assertThat(setPin(lost, field(taken, "$.resetId"), "97531")).hasStatus(200);
    assertThat(jdbc.queryForObject("select count(*) from lock where wallet_id = ?", Integer.class, msisdn)).isZero();
    assertThat(jdbc.queryForObject("select pin_hmac from credential where wallet_id = ?", String.class, msisdn))
        .isEqualTo(hmac(msisdn, "97531"));
    verify(crypto, times(0)).tokeniseNid(anyList());
  }

  @Test
  void withoutAnApprovedCareResetTheUnsignedPathIsRefused() throws Exception {
    assertCode(mvc.post().uri("/auth/v1/pin-resets").header("X-Device-Id", device.id())
        .contentType(MediaType.APPLICATION_JSON).content("{\"msisdn\":\"" + msisdn + "\",\"otp\":\"123456\"}")
        .exchange(), 403, "MW-AUTH-002");
  }
}
