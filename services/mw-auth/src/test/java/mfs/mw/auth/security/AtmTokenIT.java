package mfs.mw.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.auth.MutableClock;
import mfs.mw.auth.RedisInfrastructure;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.messaging.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The ATM cash-out token (ticket 120b2, FRS FL-11, UC-17) against real PostgreSQL and Redis. Issue:
 * {@code POST /internal/v1/atm-tokens} by mw-app-gateway with the PIN step-up assertion it got for
 * the token's transaction ({@code ATM_CASH_OUT} wallet -> partner {@code ucbatm}, the amount, the
 * idempotency key); a 6-digit token valid 10 min, stored only as an HMAC under mw-crypto {@code
 * HMAC_ATMTOKEN} bound to wallet + amount. Verify: {@code POST /internal/v1/atm-tokens/verifications}
 * by mw-adapter-atm; single use, wrong tokens counted and locked like OTPs. mw-crypto is a mock that
 * behaves like it (the HMAC a SHA-256 over a fixed key and the data).
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "spring.data.redis.timeout=3s"})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, RedisInfrastructure.class})
@ExtendWith(OutputCaptureExtension.class)
class AtmTokenIT {

  static final String PIN = "13579";
  static final String AUTHORISE = "/internal/v1/txn-authorisations";
  static final String ISSUE = "/internal/v1/atm-tokens";
  static final String VERIFY = "/internal/v1/atm-tokens/verifications";
  static final MutableClock CLOCK = new MutableClock(Instant.now());

  static Clock clock() {
    return CLOCK;
  }

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;

  @MockitoBean EventPublisher publisher;
  @MockitoBean CryptoClient crypto;
  @TestBean Clock clock;

  String msisdn;
  String deviceId;

  @Autowired
  AtmTokenIT(MockMvcTester mvc, JdbcTemplate jdbc) {
    this.mvc = mvc;
    this.jdbc = jdbc;
  }

  static String hmac(String walletId, String pin) {
    return Base64.getEncoder().encodeToString(("hmac(" + walletId + ":" + pin + ")").getBytes(StandardCharsets.UTF_8));
  }

  static String mac(byte[] data) throws Exception {
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    sha.update("test-hmac-atmtoken-key".getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(sha.digest(data));
  }

  @BeforeEach
  void setUp() {
    CLOCK.set(Instant.now());
    when(crypto.tokeniseMsisdn(anyString()))
        .thenAnswer(inv -> new CryptoClient.MsisdnToken("tok-" + inv.getArgument(0), "tokenise-msisdn.v1"));
    when(crypto.verifyPin(anyString(), any(), anyString(), anyString())).thenAnswer(inv -> new CryptoPinVerified(
        hmac(inv.getArgument(0), new String(Base64.getDecoder().decode(((EncryptedPin) inv.getArgument(1)).ciphertext()),
            StandardCharsets.UTF_8)).equals(inv.getArgument(2)), null));
    when(crypto.signJws(eq("ASSERTION_SIGN"), eq("ES256"), any(), any()))
        .thenAnswer(inv -> new CryptoJwsSigned("jws." + UUID.randomUUID(), "assertion-sign.v1", "ES256"));
    when(crypto.signHmac(eq("hmac-atmtoken"), any()))
        .thenAnswer(inv -> new CryptoClient.Mac(mac(inv.getArgument(1)), "hmac-atmtoken.v1"));
    when(crypto.verifyHmac(eq("hmac-atmtoken.v1"), any(), anyString()))
        .thenAnswer(inv -> mac(inv.getArgument(1)).equals(inv.getArgument(2)));

    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    deviceId = "dev-" + UUID.randomUUID();
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'tokenise-msisdn.v1', '{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AA==\","
        + "\"ciphertext\":\"Yw==\",\"aad\":\"aWQ=\"}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", msisdn, "tok-" + msisdn);
    jdbc.update("insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values (?, ?, 'pin-hmac.v1', now())",
        msisdn, hmac(msisdn, PIN));
    jdbc.update("insert into device (device_id, wallet_id, public_key, bound_at) values (?, ?, 'MFkw', ?)",
        deviceId, msisdn, Timestamp.from(Instant.now().minus(Duration.ofDays(3))));
  }

  MvcTestResult post(String client, String path, String body) {
    return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .header("Authorization", InternalTokens.bearer(client)).exchange();
  }

  /** The step-up: the PIN assertion for the token's transaction, as mw-app-gateway asks for it. */
  String assertion(long amount, String key) throws Exception {
    String body = "{\"walletId\":\"" + msisdn + "\",\"channel\":\"CUSTOMER_APP\",\"deviceId\":\"" + deviceId
        + "\",\"encryptedPin\":{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\""
        + Base64.getEncoder().encodeToString(PIN.getBytes(StandardCharsets.UTF_8)) + "\"},"
        + "\"txn\":{\"txnType\":\"ATM_CASH_OUT\",\"amount\":" + amount + ",\"currency\":\"BDT\","
        + "\"payer\":{\"type\":\"WALLET\",\"id\":\"" + msisdn + "\"},"
        + "\"payee\":{\"type\":\"PARTNER\",\"id\":\"ucbatm\"},\"idempotencyKey\":\"" + key + "\"}}";
    MvcTestResult r = post("mw-app-gateway", AUTHORISE, body);
    assertThat(r).hasStatusOk();
    return field(r, "$.assertion");
  }

  String issueBody(long amount, String key, String assertion) {
    return "{\"walletId\":\"" + msisdn + "\",\"channel\":\"CUSTOMER_APP\",\"deviceId\":\"" + deviceId
        + "\",\"amount\":" + amount + ",\"currency\":\"BDT\",\"idempotencyKey\":\"" + key
        + "\",\"assertion\":\"" + assertion + "\"}";
  }

  String verifyBody(long amount, String token) {
    return "{\"msisdn\":\"" + msisdn + "\",\"amount\":" + amount + ",\"currency\":\"BDT\",\"token\":\"" + token + "\"}";
  }

  /** Issues a token for {@code amount} after the step-up and returns it. */
  String issue(long amount) throws Exception {
    String key = UUID.randomUUID().toString();
    MvcTestResult r = post("mw-app-gateway", ISSUE, issueBody(amount, key, assertion(amount, key)));
    assertThat(r).hasStatus(201);
    return field(r, "$.token");
  }

  static String field(MvcTestResult r, String path) throws Exception {
    return JsonPath.read(r.getResponse().getContentAsString(), path).toString();
  }

  static void assertCode(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  static String wrong(String token) {
    return String.format("%06d", (Integer.parseInt(token) + 1) % 1_000_000);
  }

  @Test
  void aTokenIsSixDigitsValidTenMinutesAndStoredOnlyAsAnHmac(CapturedOutput output) throws Exception {
    String key = UUID.randomUUID().toString();
    MvcTestResult r = post("mw-app-gateway", ISSUE, issueBody(200000, key, assertion(200000, key)));

    assertThat(r).hasStatus(201);
    String token = field(r, "$.token");
    assertThat(token).matches("\\d{6}");
    assertThat(Instant.parse(field(r, "$.expiresAt"))).isEqualTo(CLOCK.instant().plus(Duration.ofMinutes(10)));
    List<Map<String, Object>> rows = jdbc.queryForList("select * from atm_token where wallet_id = ?", msisdn);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().values()).noneMatch(v -> v != null && v.toString().contains(token));
    assertThat(output.getAll()).doesNotContain("\"" + token + "\"").doesNotContain("=" + token);
  }

  @Test
  void theRightTokenAndAmountVerifyOnceAndAnswerTheWallet() throws Exception {
    String token = issue(200000);

    MvcTestResult ok = post("mw-adapter-atm", VERIFY, verifyBody(200000, token));
    assertThat(ok).hasStatusOk();
    assertThat(field(ok, "$.walletId")).isEqualTo(msisdn);
    assertCode(post("mw-adapter-atm", VERIFY, verifyBody(200000, token)), 401, "MW-AUTH-011");
  }

  @Test
  void anotherAmountIsAWrongTokenAndCounted() throws Exception {
    String token = issue(200000);

    assertCode(post("mw-adapter-atm", VERIFY, verifyBody(100000, token)), 401, "MW-AUTH-010");
    assertThat(post("mw-adapter-atm", VERIFY, verifyBody(200000, token))).hasStatusOk();
  }

  @Test
  void threeWrongTokensLockItEvenForTheRightOne() throws Exception {
    String token = issue(200000);
    for (int i = 0; i < 3; i++) {
      assertCode(post("mw-adapter-atm", VERIFY, verifyBody(200000, wrong(token))), 401, "MW-AUTH-010");
    }
    assertCode(post("mw-adapter-atm", VERIFY, verifyBody(200000, token)), 401, "MW-AUTH-012");
  }

  @Test
  void anExpiredTokenIsRefused() throws Exception {
    String token = issue(200000);
    CLOCK.set(CLOCK.instant().plus(Duration.ofMinutes(10)).plusSeconds(1));

    assertCode(post("mw-adapter-atm", VERIFY, verifyBody(200000, token)), 401, "MW-AUTH-011");
  }

  @Test
  void aNewTokenReplacesTheOldOne() throws Exception {
    String first = issue(200000);
    String second = issue(300000);

    if (!first.equals(second)) {
      assertCode(post("mw-adapter-atm", VERIFY, verifyBody(200000, first)), 401, "MW-AUTH-010");
    }
    assertThat(post("mw-adapter-atm", VERIFY, verifyBody(300000, second))).hasStatusOk();
  }

  @Test
  void noTokenWithoutTheStepUpAssertionForThisAmountAndKey() throws Exception {
    String key = UUID.randomUUID().toString();
    String assertion = assertion(200000, key);

    assertCode(post("mw-app-gateway", ISSUE, issueBody(200000, key, "jws.forged")), 401, "MW-AUTH-049");
    assertCode(post("mw-app-gateway", ISSUE, issueBody(900000, key, assertion)), 401, "MW-AUTH-049");
    assertCode(post("mw-app-gateway", ISSUE, issueBody(200000, UUID.randomUUID().toString(), assertion)), 401,
        "MW-AUTH-049");
    assertThat(jdbc.queryForObject("select count(*) from atm_token where wallet_id = ?", Integer.class, msisdn))
        .isZero();
  }

  @Test
  void theStepUpAssertionIsSpentByTheIssueSoItCannotIssueASecondToken() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = issueBody(200000, key, assertion(200000, key));

    assertThat(post("mw-app-gateway", ISSUE, body)).hasStatus(201);
    assertCode(post("mw-app-gateway", ISSUE, body), 401, "MW-AUTH-049");
  }

  @Test
  void theAtmAdapterReachesNothingButTheTokenCheck() {
    assertCode(post("mw-adapter-atm", AUTHORISE, "{}"), 403, "MW-AUTH-002");
    assertThat(mvc.get().uri("/internal/v1/devices/" + deviceId)
        .header("Authorization", InternalTokens.bearer("mw-adapter-atm")).exchange()).hasStatus(403);
    assertThat(mvc.get().uri("/internal/v1/devices/" + deviceId)
        .header("Authorization", InternalTokens.bearer("mw-ussd")).exchange()).hasStatus(403);
    assertThat(mvc.get().uri("/internal/v1/devices/" + deviceId)
        .header("Authorization", InternalTokens.bearer("mw-app-gateway")).exchange()).hasStatusOk();
  }

  @Test
  void onlyTheGatewayIssuesAndOnlyTheAtmAdapterVerifies() throws Exception {
    String key = UUID.randomUUID().toString();
    String assertion = assertion(200000, key);
    assertCode(post("mw-adapter-atm", ISSUE, issueBody(200000, key, assertion)), 403, "MW-AUTH-002");
    String token = issue(200000);
    assertCode(post("mw-app-gateway", VERIFY, verifyBody(200000, token)), 403, "MW-AUTH-002");
  }

  @Test
  void anUnknownWalletOrNoTokenIsRefused() {
    assertCode(post("mw-adapter-atm", VERIFY, verifyBody(200000, "123456")), 401, "MW-AUTH-011");
    String other = "{\"msisdn\":\"01999999999\",\"amount\":200000,\"currency\":\"BDT\",\"token\":\"123456\"}";
    assertCode(post("mw-adapter-atm", VERIFY, other), 401, "MW-AUTH-011");
  }
}
