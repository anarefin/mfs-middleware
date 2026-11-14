package mfs.mw.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.ValkeyInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.CryptoPinDerived;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.messaging.EventPublisher;
import mfs.mw.auth.security.DeviceBindingIT.Device;
import mfs.mw.auth.security.DeviceBindingIT.Signed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Login, tokens and the key documents against real PostgreSQL and Valkey (spec 18.7, D21; ticket
 * 69). mw-crypto is a mock that behaves like it: a PIN "ciphertext" is the base64 of the PIN, its
 * HMAC {@code hmac(<walletId>:<pin>)}; JWS are signed RS256 by an in-test RSA key whose public JWK is
 * mw-crypto's JWT_SIGN set, so the tokens are verified here exactly as APISIX and mw-app-gateway
 * verify them (from {@code /auth/v1/.well-known/jwks.json}). The wallet and its bound device are
 * inserted as the projection and a binding would leave them.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "spring.data.redis.timeout=3s",
    "mw.auth.seed-api.enabled=true"})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ValkeyInfrastructure.class})
class LoginIT {

  static final String PIN = "13579";
  static RSAKey jwtKey;
  static RSAKey pinKeyV1;
  static RSAKey pinKeyV2;

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate valkey;

  @MockitoBean EventPublisher publisher;
  @MockitoBean CryptoClient crypto;

  String msisdn;
  Device device;

  @Autowired
  LoginIT(MockMvcTester mvc, JdbcTemplate jdbc, StringRedisTemplate valkey) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.valkey = valkey;
  }

  static RSAKey rsa(String kid, KeyUse use) throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    var kp = g.generateKeyPair();
    return new RSAKey.Builder((RSAPublicKey) kp.getPublic()).privateKey((RSAPrivateKey) kp.getPrivate())
        .keyID(kid).keyUse(use).algorithm(use == KeyUse.SIGNATURE ? JWSAlgorithm.RS256
            : com.nimbusds.jose.JWEAlgorithm.RSA_OAEP_256).build();
  }

  @BeforeAll
  static void keys() throws Exception {
    jwtKey = rsa("jwt-sign.v1", KeyUse.SIGNATURE);
    pinKeyV1 = rsa("pin-transport.v1", KeyUse.ENCRYPTION);
    pinKeyV2 = rsa("pin-transport.v2", KeyUse.ENCRYPTION);
  }

  static String hmac(String walletId, String pin) {
    return Base64.getEncoder().encodeToString(("hmac(" + walletId + ":" + pin + ")").getBytes(StandardCharsets.UTF_8));
  }

  static EncryptedPin encrypted(String pin) {
    return new EncryptedPin("pin-transport.v2", Base64.getEncoder().encodeToString(pin.getBytes(StandardCharsets.UTF_8)));
  }

  static String clear(EncryptedPin p) {
    return new String(Base64.getDecoder().decode(p.ciphertext()), StandardCharsets.UTF_8);
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
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
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
          .type(new JOSEObjectType("at+jwt")).keyID(jwtKey.getKeyID()).build(), JWTClaimsSet.parse(claims));
      jwt.sign(new RSASSASigner(jwtKey));
      return new CryptoJwsSigned(jwt.serialize(), jwtKey.getKeyID(), "RS256");
    });
    when(crypto.jwks("JWT_SIGN")).thenAnswer(inv -> new JWKSet(jwtKey.toPublicJWK()).toJSONObject());
    when(crypto.jwks("PIN_TRANSPORT"))
        .thenAnswer(inv -> new JWKSet(List.of(pinKeyV2.toPublicJWK(), pinKeyV1.toPublicJWK())).toJSONObject());
    when(crypto.decrypt(any())).thenAnswer(inv -> msisdn.getBytes(StandardCharsets.UTF_8));

    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'tokenise-msisdn.v1', '{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AA==\","
        + "\"ciphertext\":\"Yw==\",\"aad\":\"aWQ=\"}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", msisdn, "tok-" + msisdn);
    jdbc.update("insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values (?, ?, 'pin-hmac.v1', now())",
        msisdn, hmac(msisdn, PIN));
    device = bindDevice(msisdn);
  }

  Device bindDevice(String wallet) throws Exception {
    Device d = Device.create();
    jdbc.update("insert into device (device_id, wallet_id, public_key, bound_at) values (?, ?, ?, ?)",
        d.id(), wallet, d.publicKey(), Timestamp.from(Instant.now()));
    return d;
  }

  MvcTestResult signedPost(Device d, String path, String body) throws Exception {
    Signed s = DeviceBindingIT.sign(d, "POST", path, body);
    return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .header("X-Device-Id", s.deviceId()).header("X-Timestamp", s.timestamp()).header("X-Nonce", s.nonce())
        .header("X-Signature", s.signature()).exchange();
  }

  static String pinJson(String pin) {
    EncryptedPin p = encrypted(pin);
    return "{\"keyId\":\"" + p.keyId() + "\",\"ciphertext\":\"" + p.ciphertext() + "\"}";
  }

  MvcTestResult login(Device d, String pin) throws Exception {
    return signedPost(d, "/auth/v1/sessions", "{\"msisdn\":\"" + msisdn + "\",\"encryptedPin\":" + pinJson(pin) + "}");
  }

  MvcTestResult refresh(Device d, String token) throws Exception {
    return signedPost(d, "/auth/v1/tokens/refresh", "{\"refreshToken\":\"" + token + "\"}");
  }

  static String field(MvcTestResult r, String path) throws Exception {
    return JsonPath.read(r.getResponse().getContentAsString(), path).toString();
  }

  static void assertCode(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  /** The access token verified against the served JWK Set, as the edge and the gateway verify it. */
  JWTClaimsSet verified(String accessToken) throws Exception {
    JWKSet served = JWKSet.parse(mvc.get().uri("/auth/v1/.well-known/jwks.json").exchange().getResponse()
        .getContentAsString());
    SignedJWT jwt = SignedJWT.parse(accessToken);
    RSAKey key = (RSAKey) served.getKeyByKeyId(jwt.getHeader().getKeyID());
    assertThat(jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(key))).isTrue();
    return jwt.getJWTClaimsSet();
  }

  // --- login ---

  @Test
  void aPinLoginOnTheBoundDeviceGetsVerifiableTokensAndOpensTheSession() throws Exception {
    MvcTestResult r = login(device, PIN);
    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
    assertThat(r).bodyJson().extractingPath("$.expiresIn").isEqualTo(300);
    assertThat(r).bodyJson().extractingPath("$.refreshExpiresIn").isEqualTo(2592000);
    assertThat(r).bodyJson().extractingPath("$.idleTimeoutSeconds").isEqualTo(300);
    assertThat(r).bodyJson().extractingPath("$.pinSet").isEqualTo(true);

    JWTClaimsSet c = verified(field(r, "$.accessToken"));
    assertThat(c.getIssuer()).isEqualTo("mw-auth");
    assertThat(c.getAudience()).containsExactly("mw-app");
    assertThat(c.getSubject()).isEqualTo(msisdn);
    assertThat(c.getStringClaim("msisdn")).isEqualTo(msisdn);
    assertThat(c.getStringClaim("role")).isEqualTo("customer");
    assertThat(c.getStringClaim("deviceId")).isEqualTo(device.id());
    assertThat(c.getStringListClaim("amr")).containsExactly("pin", "device");
    assertThat(c.getStringClaim("sid")).isEqualTo(field(r, "$.sessionId"));

    assertThat(valkey.getExpire("sess:" + msisdn + ":" + device.id())).isPositive();
    String refreshToken = field(r, "$.refreshToken");
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where wallet_id = ? and token_hash = ?",
        Integer.class, msisdn, mfs.mw.auth.service.TokenService.hash(refreshToken))).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where token_hash = ?", Integer.class,
        refreshToken)).as("never stored in clear").isZero();
  }

  @Test
  void aWrongPinIsCountedAndRefused() throws Exception {
    assertCode(login(device, "24680"), 401, "MW-AUTH-040");
    assertCode(login(device, "24680"), 401, "MW-AUTH-040");
    assertThat(jdbc.queryForObject("select count from attempt where wallet_id = ?", Integer.class, msisdn)).isEqualTo(2);
    assertThat(login(device, PIN)).hasStatus(200);
    assertThat(jdbc.queryForObject("select count(*) from attempt where wallet_id = ?", Integer.class, msisdn))
        .as("a correct PIN resets the count").isZero();
  }

  @Test
  void anUnknownMsisdnIsRefusedAsADeviceNotBound() throws Exception {
    msisdn = "01799999999";
    assertCode(login(device, PIN), 401, "MW-AUTH-047");
  }

  @Test
  void aWalletNotActiveAtTheCoreIsRefusedBeforeThePin() throws Exception {
    jdbc.update("update wallet set status = 'BLOCKED' where wallet_id = ?", msisdn);
    assertCode(login(device, "00000"), 403, "MW-AUTH-043");
    assertThat(jdbc.queryForObject("select count(*) from attempt where wallet_id = ?", Integer.class, msisdn)).isZero();
  }

  @Test
  void locksAreCheckedBeforeThePin() throws Exception {
    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor) values (?, 'PIN', 1, ?, 'test', 'it')",
        msisdn, Timestamp.from(Instant.now().plusSeconds(3600)));
    MvcTestResult temporary = login(device, PIN);
    assertCode(temporary, 423, "MW-AUTH-041");
    assertThat(temporary).bodyJson().extractingPath("$.details[0].field").isEqualTo("retryAfter");

    jdbc.update("update lock set until = null, level = 3 where wallet_id = ?", msisdn);
    assertCode(login(device, PIN), 423, "MW-AUTH-042");

    jdbc.update("update lock set kind = 'ADMIN' where wallet_id = ?", msisdn);
    assertCode(login(device, PIN), 403, "MW-AUTH-043");

    jdbc.update("update lock set kind = 'PIN', until = ? where wallet_id = ?",
        Timestamp.from(Instant.now().minusSeconds(1)), msisdn);
    assertThat(login(device, PIN)).as("an expired lock").hasStatus(200);
  }

  @Test
  void aDeviceNotBoundToTheWalletIsRefusedBeforeThePin() throws Exception {
    Device stranger = Device.create();
    assertCode(login(stranger, PIN), 401, "MW-AUTH-047");
    String other = "0172" + String.format("%07d", (int) (Math.random() * 10_000_000));
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'k', '{}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", other, "tok-" + other);
    Device othersDevice = bindDevice(other);
    assertCode(login(othersDevice, PIN), 401, "MW-AUTH-047");
  }

  @Test
  void aLoginSignedByAnotherKeyOrReplayedIsRefused() throws Exception {
    String body = "{\"msisdn\":\"" + msisdn + "\",\"encryptedPin\":" + pinJson(PIN) + "}";
    Signed byOther = DeviceBindingIT.sign(Device.create(), "POST", "/auth/v1/sessions", body);
    var forged = mvc.post().uri("/auth/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(body)
        .header("X-Device-Id", device.id()).header("X-Timestamp", byOther.timestamp())
        .header("X-Nonce", byOther.nonce()).header("X-Signature", byOther.signature()).exchange();
    assertCode(forged, 401, "MW-AUTH-004");

    Signed s = DeviceBindingIT.sign(device, "POST", "/auth/v1/sessions", body);
    for (int i = 0; i < 2; i++) {
      var r = mvc.post().uri("/auth/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(body)
          .header("X-Device-Id", s.deviceId()).header("X-Timestamp", s.timestamp()).header("X-Nonce", s.nonce())
          .header("X-Signature", s.signature()).exchange();
      if (i == 0) {
        assertThat(r).hasStatus(200);
      } else {
        assertCode(r, 401, "MW-AUTH-006");
      }
    }
  }

  @Test
  void aWalletWithAPinCannotLogInWithAnOtpAndOneWithoutCannotUseAPin() throws Exception {
    assertCode(signedPost(device, "/auth/v1/sessions", "{\"msisdn\":\"" + msisdn + "\",\"otp\":\"123456\"}"), 400,
        "MW-VAL-001");
    jdbc.update("delete from credential where wallet_id = ?", msisdn);
    assertCode(login(device, PIN), 403, "MW-AUTH-045");
  }

  @Test
  void mwCryptoDownFailsClosed() throws Exception {
    org.mockito.Mockito.doThrow(
            new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "down").withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        .when(crypto).verifyPin(anyString(), any(), anyString(), anyString());
    MvcTestResult r = login(device, PIN);
    assertCode(r, 503, "MW-SYS-050");
    assertThat(r).bodyJson().extractingPath("$.retryable").isEqualTo(true);
    assertThat(valkey.hasKey("sess:" + msisdn + ":" + device.id())).isFalse();
  }

  // --- refresh, rotation, reuse, logout ---

  @Test
  void aRefreshRotatesTheTokenAndKeepsTheSession() throws Exception {
    MvcTestResult first = login(device, PIN);
    String sid = field(first, "$.sessionId");
    MvcTestResult second = refresh(device, field(first, "$.refreshToken"));
    assertThat(second).hasStatus(200);
    assertThat(field(second, "$.refreshToken")).isNotEqualTo(field(first, "$.refreshToken"));
    assertThat(field(second, "$.sessionId")).isEqualTo(sid);
    JWTClaimsSet c = verified(field(second, "$.accessToken"));
    assertThat(c.getStringClaim("sid")).isEqualTo(sid);
    assertThat(c.getStringClaim("msisdn")).isEqualTo(msisdn);
    assertThat(c.getStringListClaim("amr")).containsExactly("pin", "device");
    assertThat(refresh(device, field(second, "$.refreshToken"))).hasStatus(200);
  }

  @Test
  void aRotatedTokenUsedAgainRevokesTheWholeFamily() throws Exception {
    MvcTestResult first = login(device, PIN);
    String used = field(first, "$.refreshToken");
    String current = field(refresh(device, used), "$.refreshToken");

    assertCode(refresh(device, used), 401, "MW-AUTH-048");
    assertCode(refresh(device, current), 401, "MW-AUTH-048");
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where family = ?::uuid and not revoked",
        Integer.class, field(first, "$.sessionId"))).isZero();
  }

  @Test
  void aRefreshTokenFromAnotherDeviceRevokesTheSession() throws Exception {
    MvcTestResult first = login(device, PIN);
    String other = "0173" + String.format("%07d", (int) (Math.random() * 10_000_000));
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'k', '{}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", other, "tok-" + other);
    Device thief = bindDevice(other);
    assertCode(refresh(thief, field(first, "$.refreshToken")), 401, "MW-AUTH-047");
    assertCode(refresh(device, field(first, "$.refreshToken")), 401, "MW-AUTH-048");
  }

  @Test
  void anUnknownRefreshTokenIsRefused() throws Exception {
    assertCode(refresh(device, "A".repeat(43)), 401, "MW-AUTH-001");
  }

  @Test
  void aLockedWalletCannotRefresh() throws Exception {
    MvcTestResult first = login(device, PIN);
    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor) values (?, 'ADMIN', 1, null, 'test', 'it')",
        msisdn);
    assertCode(refresh(device, field(first, "$.refreshToken")), 403, "MW-AUTH-043");
  }

  @Test
  void logoutEndsTheSessionAndItsRefreshTokens() throws Exception {
    MvcTestResult first = login(device, PIN);
    String access = field(first, "$.accessToken");
    var out = mvc.post().uri("/auth/v1/sessions/logout").header("Authorization", "Bearer " + access).exchange();
    assertThat(out).hasStatus(204);
    assertThat(valkey.hasKey("sess:" + msisdn + ":" + device.id())).isFalse();
    assertCode(refresh(device, field(first, "$.refreshToken")), 401, "MW-AUTH-048");
  }

  @Test
  void logoutRefusesATokenNotIssuedHere() throws Exception {
    RSAKey foreign = rsa("jwt-sign.v1", KeyUse.SIGNATURE);
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt"))
        .keyID("jwt-sign.v1").build(), new JWTClaimsSet.Builder().issuer("mw-auth").audience("mw-app")
        .subject(msisdn).claim("sid", "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d")
        .expirationTime(java.util.Date.from(Instant.now().plusSeconds(60))).build());
    jwt.sign(new RSASSASigner(foreign));
    assertCode(mvc.post().uri("/auth/v1/sessions/logout").header("Authorization", "Bearer " + jwt.serialize())
        .exchange(), 401, "MW-AUTH-001");
  }

  // --- first PIN ---

  @Test
  void aWalletWithoutAPinLogsInWithAnOtpThenSetsItsPin() throws Exception {
    jdbc.update("delete from credential where wallet_id = ?", msisdn);
    var ask = mvc.post().uri("/auth/v1/otp").header("X-Device-Id", device.id()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"msisdn\":\"" + msisdn + "\",\"purpose\":\"LOGIN\"}").exchange();
    assertThat(ask).hasStatus(202);
    ArgumentCaptor<Object> sms = ArgumentCaptor.forClass(Object.class);
    org.mockito.Mockito.verify(publisher).publish(eq("mw.notify.request.v1"), eq(msisdn), any(), sms.capture());
    var notice = (mfs.mw.auth.dto.NotificationRequest) sms.getValue();
    assertThat(notice.templateCode()).isEqualTo("LOGIN_OTP");

    MvcTestResult otpLogin = signedPost(device, "/auth/v1/sessions",
        "{\"msisdn\":\"" + msisdn + "\",\"otp\":\"" + notice.params().get("otp") + "\"}");
    assertThat(otpLogin).hasStatus(200);
    assertThat(otpLogin).bodyJson().extractingPath("$.pinSet").isEqualTo(false);
    assertThat(verified(field(otpLogin, "$.accessToken")).getStringListClaim("amr")).containsExactly("otp", "device");

    String bearer = "Bearer " + field(otpLogin, "$.accessToken");
    var weak = mvc.post().uri("/auth/v1/pin").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + pinJson("11111") + "}").exchange();
    assertCode(weak, 400, "MW-AUTH-044");

    var set = mvc.post().uri("/auth/v1/pin").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + pinJson("24680") + "}").exchange();
    assertThat(set).hasStatus(200);
    assertThat(set).bodyJson().extractingPath("$.pinSet").isEqualTo(true);
    assertThat(verified(field(set, "$.accessToken")).getStringListClaim("amr")).containsExactly("pin", "device");
    assertThat(field(set, "$.sessionId")).isNotEqualTo(field(otpLogin, "$.sessionId"));
    assertCode(refresh(device, field(otpLogin, "$.refreshToken")), 401, "MW-AUTH-048");

    assertThat(login(device, "24680")).hasStatus(200);
    var again = mvc.post().uri("/auth/v1/pin").header("Authorization", "Bearer " + field(set, "$.accessToken"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"encryptedPin\":" + pinJson("97531") + "}").exchange();
    assertCode(again, 403, "MW-AUTH-002");
  }

  @Test
  void aLoginOtpIsRefusedForAWalletWithAPin() throws Exception {
    var ask = mvc.post().uri("/auth/v1/otp").header("X-Device-Id", device.id()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"msisdn\":\"" + msisdn + "\",\"purpose\":\"LOGIN\"}").exchange();
    assertCode(ask, 403, "MW-AUTH-002");
  }

  // --- key documents ---

  @Test
  void theJwksHasOnlyPublicMembersAndTheDiscoveryNamesIt() throws Exception {
    when(crypto.jwks("JWT_SIGN")).thenAnswer(inv -> {
      Map<String, Object> pub = new java.util.LinkedHashMap<>(jwtKey.toPublicJWK().toJSONObject());
      pub.put("x5c", List.of("ignored"));
      return Map.of("keys", List.of(pub));
    });
    MvcTestResult jwks = mvc.get().uri("/auth/v1/.well-known/jwks.json").exchange();
    assertThat(jwks).hasStatus(200);
    String body = jwks.getResponse().getContentAsString();
    assertThat(body).contains("\"kid\":\"jwt-sign.v1\"").contains("\"kty\":\"RSA\"").doesNotContain("x5c")
        .doesNotContain("\"d\"");

    MvcTestResult d = mvc.get().uri("/auth/v1/.well-known/openid-configuration").exchange();
    assertThat(d).bodyJson().extractingPath("$.issuer").isEqualTo("mw-auth");
    assertThat(d).bodyJson().extractingPath("$.jwks_uri").isEqualTo("http://mw-auth:8080/auth/v1/.well-known/jwks.json");
  }

  @Test
  void thePinKeyIsTheNewestTransportKeyAsPem() throws Exception {
    MvcTestResult r = mvc.get().uri("/auth/v1/pin-key").exchange();
    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.keyId").isEqualTo("pin-transport.v2");
    String pem = field(r, "$.publicKeyPem");
    byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", ""));
    assertThat(der).isEqualTo(pinKeyV2.toRSAPublicKey().getEncoded());
  }

  // --- the dev seed API ---

  @Test
  void theSeedApiSetsFirstPinsOnce() throws Exception {
    jdbc.update("delete from credential where wallet_id = ?", msisdn);
    String body = "{\"pins\":[{\"walletId\":\"" + msisdn + "\",\"encryptedPin\":" + pinJson(PIN) + "},"
        + "{\"walletId\":\"01700000000\",\"encryptedPin\":" + pinJson(PIN) + "}]}";
    var first = mvc.post().uri("/internal/v1/seed/pins").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    assertThat(first).hasStatus(200);
    assertThat(first).bodyJson().extractingPath("$.results[0].outcome").isEqualTo("CREATED");
    assertThat(first).bodyJson().extractingPath("$.results[1].outcome").isEqualTo("UNKNOWN_WALLET");
    var second = mvc.post().uri("/internal/v1/seed/pins").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    assertThat(second).bodyJson().extractingPath("$.results[0].outcome").isEqualTo("UNCHANGED");
    assertThat(login(device, PIN)).hasStatus(200);
  }
}
