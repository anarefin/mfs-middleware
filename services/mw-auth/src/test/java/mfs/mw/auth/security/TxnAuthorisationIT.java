package mfs.mw.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import mfs.mw.auth.MutableClock;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.ValkeyInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.messaging.EventPublisher;
import mfs.mw.auth.util.TxnHash;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The transaction authorisation assertion (spec 18.7, D22; ticket 71) against real PostgreSQL and
 * Valkey: {@code POST /internal/v1/txn-authorisations} with a services token of a channel. mw-crypto
 * is a mock that behaves like it: a PIN "ciphertext" is the base64 of the PIN, its HMAC {@code
 * hmac(<walletId>:<pin>)}, and ASSERTION_SIGN signs ES256 with an in-test P-256 key whose public JWK
 * is the set mw-auth serves at {@code /auth/v1/.well-known/assertion-jwks.json}, so each assertion is
 * verified here as mw-core-mock verifies it. The clock is moved by hand for the 60 s life.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "spring.data.redis.timeout=3s"})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ValkeyInfrastructure.class})
class TxnAuthorisationIT {

  static final String PIN = "13579";
  static final String PATH = "/internal/v1/txn-authorisations";
  static ECKey assertionKey;

  static final MutableClock CLOCK = new MutableClock(Instant.now());

  /** The service's clock, moved by hand ({@code @TestBean} factory). */
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
  TxnAuthorisationIT(MockMvcTester mvc, JdbcTemplate jdbc) {
    this.mvc = mvc;
    this.jdbc = jdbc;
  }

  @BeforeAll
  static void keys() throws Exception {
    assertionKey = new ECKeyGenerator(Curve.P_256).keyID("assertion-sign.v1").algorithm(JWSAlgorithm.ES256).generate();
  }

  static String hmac(String walletId, String pin) {
    return Base64.getEncoder().encodeToString(("hmac(" + walletId + ":" + pin + ")").getBytes(StandardCharsets.UTF_8));
  }

  static String clear(EncryptedPin p) {
    return new String(Base64.getDecoder().decode(p.ciphertext()), StandardCharsets.UTF_8);
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    CLOCK.set(Instant.now());
    when(crypto.tokeniseMsisdn(anyString()))
        .thenAnswer(inv -> new CryptoClient.MsisdnToken("tok-" + inv.getArgument(0), "tokenise-msisdn.v1"));
    when(crypto.verifyPin(anyString(), any(), anyString(), anyString())).thenAnswer(inv -> new CryptoPinVerified(
        hmac(inv.getArgument(0), clear(inv.getArgument(1))).equals(inv.getArgument(2)), null));
    when(crypto.signJws(eq("ASSERTION_SIGN"), eq("ES256"), any(), any())).thenAnswer(inv -> {
      Map<String, Object> header = inv.getArgument(2);
      Map<String, Object> claims = inv.getArgument(3);
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
          .type(new JOSEObjectType(String.valueOf(header.get("typ")))).keyID(assertionKey.getKeyID()).build(),
          JWTClaimsSet.parse(claims));
      jwt.sign(new ECDSASigner(assertionKey));
      return new CryptoJwsSigned(jwt.serialize(), assertionKey.getKeyID(), "ES256");
    });
    when(crypto.jwks("ASSERTION_SIGN")).thenAnswer(inv -> new JWKSet(assertionKey.toPublicJWK()).toJSONObject());

    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    deviceId = "dev-" + UUID.randomUUID();
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'tokenise-msisdn.v1', '{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AA==\","
        + "\"ciphertext\":\"Yw==\",\"aad\":\"aWQ=\"}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", msisdn, "tok-" + msisdn);
    jdbc.update("insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values (?, ?, 'pin-hmac.v1', now())",
        msisdn, hmac(msisdn, PIN));
    bindDevice(Instant.now().minus(Duration.ofDays(3)));
  }

  void bindDevice(Instant boundAt) {
    jdbc.update("insert into device (device_id, wallet_id, public_key, bound_at) values (?, ?, 'MFkw', ?)",
        deviceId, msisdn, Timestamp.from(boundAt));
  }

  static String pinJson(String pin) {
    return "{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\""
        + Base64.getEncoder().encodeToString(pin.getBytes(StandardCharsets.UTF_8)) + "\"}";
  }

  String txnJson(long amount, String idempotencyKey) {
    return "{\"txnType\":\"P2P\",\"amount\":" + amount + ",\"currency\":\"BDT\","
        + "\"payer\":{\"type\":\"WALLET\",\"id\":\"" + msisdn + "\"},"
        + "\"payee\":{\"type\":\"WALLET\",\"id\":\"01811000002\"},\"idempotencyKey\":\"" + idempotencyKey + "\"}";
  }

  String appBody(String pin, long amount, String key) {
    return "{\"walletId\":\"" + msisdn + "\",\"channel\":\"CUSTOMER_APP\",\"deviceId\":\"" + deviceId
        + "\",\"encryptedPin\":" + pinJson(pin) + ",\"txn\":" + txnJson(amount, key) + "}";
  }

  MvcTestResult authorise(String body) {
    return authorise("mw-app-gateway", body);
  }

  MvcTestResult authorise(String client, String body) {
    return mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
        .header("Authorization", InternalTokens.bearer(client)).exchange();
  }

  static String field(MvcTestResult r, String path) throws Exception {
    return JsonPath.read(r.getResponse().getContentAsString(), path).toString();
  }

  static void assertCode(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  /** The assertion verified against the served assertion JWK Set, as the core verifies it. */
  JWTClaimsSet verified(String assertion) throws Exception {
    JWKSet served = JWKSet.parse(mvc.get().uri("/auth/v1/.well-known/assertion-jwks.json").exchange().getResponse()
        .getContentAsString());
    SignedJWT jwt = SignedJWT.parse(assertion);
    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    ECKey key = (ECKey) served.getKeyByKeyId(jwt.getHeader().getKeyID());
    assertThat(key.isPrivate()).isFalse();
    assertThat(jwt.verify(new ECDSAVerifier(key))).isTrue();
    return jwt.getJWTClaimsSet();
  }

  @Test
  void theRightPinGivesASignedAssertionBoundToTheTxn() throws Exception {
    String key = UUID.randomUUID().toString();
    MvcTestResult r = authorise(appBody(PIN, 50000, key));
    assertThat(r).hasStatusOk();
    JWTClaimsSet c = verified(field(r, "$.assertion"));
    assertThat(c.getIssuer()).isEqualTo("mw-auth");
    assertThat(c.getAudience()).containsExactly("core");
    assertThat(c.getSubject()).isEqualTo(msisdn);
    assertThat(UUID.fromString(c.getJWTID()).version()).isEqualTo(7);
    assertThat(Duration.between(c.getIssueTime().toInstant(), c.getExpirationTime().toInstant())).isEqualTo(Duration.ofSeconds(60));
    assertThat(c.getStringClaim("txnHash")).isEqualTo(TxnHash.of("P2P", 50000, "BDT",
        new TxnHash.Party("WALLET", msisdn), new TxnHash.Party("WALLET", "01811000002"), key, "CUSTOMER_APP"));
    assertThat(c.getStringClaim("channel")).isEqualTo("CUSTOMER_APP");
    assertThat(c.getStringClaim("authMethod")).isEqualTo("PIN");
    assertThat(c.getStringClaim("deviceId")).isEqualTo(deviceId);
    assertThat(c.getBooleanClaim("newDevice")).isFalse();
    assertThat(field(r, "$.expiresAt")).isEqualTo(c.getExpirationTime().toInstant().toString());
  }

  @Test
  void ussdNamesTheWalletByMsisdnAndCarriesNoDevice() throws Exception {
    String body = "{\"msisdn\":\"+88" + msisdn + "\",\"channel\":\"USSD\",\"encryptedPin\":" + pinJson(PIN)
        + ",\"txn\":" + txnJson(1000, UUID.randomUUID().toString()) + "}";
    MvcTestResult r = authorise("mw-ussd", body);
    assertThat(r).hasStatusOk();
    JWTClaimsSet c = verified(field(r, "$.assertion"));
    assertThat(c.getSubject()).isEqualTo(msisdn);
    assertThat(c.getStringClaim("channel")).isEqualTo("USSD");
    assertThat(c.getClaim("deviceId")).isNull();
  }

  @Test
  void aWalletWithADeviceBoundWithin24HoursGetsNewDevice() throws Exception {
    jdbc.update("update device set bound_at = ? where device_id = ?", Timestamp.from(CLOCK.instant().minusSeconds(3600)), deviceId);
    MvcTestResult r = authorise(appBody(PIN, 1000, UUID.randomUUID().toString()));
    assertThat(r).hasStatusOk();
    assertThat(verified(field(r, "$.assertion")).getBooleanClaim("newDevice")).isTrue();
  }

  @Test
  void aWrongPinIsRefusedAndCounted() {
    assertCode(authorise(appBody("24680", 1000, UUID.randomUUID().toString())), 401, ErrorCodes.AUTH_WRONG_PIN);
    assertCode(authorise(appBody("24680", 1000, UUID.randomUUID().toString())), 401, ErrorCodes.AUTH_WRONG_PIN);
    assertThat(jdbc.queryForObject("select count from attempt where wallet_id = ?", Integer.class, msisdn)).isEqualTo(2);
    verify(crypto, never()).signJws(any(), any(), any(), any());
    assertThat(authorise(appBody(PIN, 1000, UUID.randomUUID().toString()))).hasStatusOk();
    assertThat(jdbc.queryForObject("select count(*) from attempt where wallet_id = ?", Integer.class, msisdn)).isZero();
  }

  @Test
  void aLockedPinIsRefusedWithoutAPinCheck() {
    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor) values (?, 'PIN', 1, ?, 'test', 'it')",
        msisdn, Timestamp.from(CLOCK.instant().plusSeconds(600)));
    assertCode(authorise(appBody(PIN, 1000, UUID.randomUUID().toString())), 423, ErrorCodes.AUTH_PIN_LOCKED);
    verify(crypto, never()).verifyPin(anyString(), any(), anyString(), anyString());
  }

  @Test
  void anAdminLockOrAnInactiveWalletIsRefused() {
    jdbc.update("update wallet set status = 'BLOCKED' where wallet_id = ?", msisdn);
    assertCode(authorise(appBody(PIN, 1000, UUID.randomUUID().toString())), 403, ErrorCodes.AUTH_WALLET_LOCKED);
  }

  @Test
  void aWalletWithoutAPinIsRefused() {
    jdbc.update("delete from credential where wallet_id = ?", msisdn);
    assertCode(authorise(appBody(PIN, 1000, UUID.randomUUID().toString())), 403, ErrorCodes.AUTH_PIN_NOT_SET);
  }

  @Test
  void aRepeatWithTheSameKeyInsideTheLifeReturnsTheSameAssertionWithoutASecondPinCheck() throws Exception {
    String key = UUID.randomUUID().toString();
    MvcTestResult first = authorise(appBody(PIN, 5000, key));
    CLOCK.advance(Duration.ofSeconds(30));
    MvcTestResult again = authorise(appBody(PIN, 5000, key));
    assertThat(again).hasStatusOk();
    assertThat(field(again, "$.assertion")).isEqualTo(field(first, "$.assertion"));
    verify(crypto, times(1)).verifyPin(anyString(), any(), anyString(), anyString());
  }

  @Test
  void theSameKeyForAnotherTxnIsAConflict() {
    String key = UUID.randomUUID().toString();
    assertThat(authorise(appBody(PIN, 5000, key))).hasStatusOk();
    assertCode(authorise(appBody(PIN, 5001, key)), 409, "MW-IDEM-001");
  }

  @Test
  void afterTheLifeTheCustomerEntersThePinAgainAndGetsANewAssertion() throws Exception {
    String key = UUID.randomUUID().toString();
    MvcTestResult first = authorise(appBody(PIN, 5000, key));
    CLOCK.advance(Duration.ofSeconds(61));
    clearInvocations(crypto);
    MvcTestResult again = authorise(appBody(PIN, 5000, key));
    assertThat(again).hasStatusOk();
    verify(crypto, times(1)).verifyPin(anyString(), any(), anyString(), anyString());
    assertThat(verified(field(again, "$.assertion")).getJWTID()).isNotEqualTo(verified(field(first, "$.assertion")).getJWTID());
  }

  @Test
  void aDeviceNotBoundToTheWalletIsRefused() {
    String body = appBody(PIN, 1000, UUID.randomUUID().toString()).replace(deviceId, "dev-other");
    assertCode(authorise(body), 401, ErrorCodes.AUTH_DEVICE_MISMATCH);
  }

  @Test
  void onlyTheChannelsMayAsk() {
    assertCode(authorise("mw-admin-api", appBody(PIN, 1000, UUID.randomUUID().toString())), 403, ErrorCodes.AUTH_FORBIDDEN);
    assertCode(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(appBody(PIN, 1000, "k")).exchange(),
        401, ErrorCodes.AUTH_UNAUTHENTICATED);
  }

  @Test
  void exactlyOneOfWalletIdAndMsisdn() {
    String both = appBody(PIN, 1000, "k1").replace("{\"walletId\"", "{\"msisdn\":\"" + msisdn + "\",\"walletId\"");
    assertCode(authorise(both), 400, ErrorCodes.VAL_INVALID);
    String none = appBody(PIN, 1000, "k2").replace("\"walletId\":\"" + msisdn + "\",", "");
    assertCode(authorise(none), 400, ErrorCodes.VAL_INVALID);
    assertCode(authorise(appBody(PIN, 0, "k3")), 400, ErrorCodes.VAL_INVALID);
  }

  @Test
  void mwCryptoDownFailsClosed() {
    doThrow(new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "down").withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        .when(crypto).verifyPin(anyString(), any(), anyString(), anyString());
    assertCode(authorise(appBody(PIN, 1000, UUID.randomUUID().toString())), 503, ErrorCodes.SYS_CRYPTO_UNAVAILABLE);
  }
}
