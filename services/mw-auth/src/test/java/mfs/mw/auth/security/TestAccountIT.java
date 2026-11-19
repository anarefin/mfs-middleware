package mfs.mw.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.RedisInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.CryptoPinDerived;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.messaging.EventPublisher;
import mfs.mw.auth.security.DeviceBindingIT.Device;
import mfs.mw.auth.security.DeviceBindingIT.Signed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * The dev-only account test API ({@code auth-test.v1}, {@code mw.auth.seed-api.enabled=true}) against
 * PostgreSQL and Redis: the state it shows, and that unlock, unbind and set-PIN change what the app's
 * login then sees. mw-crypto is the same behaving mock as in {@link LoginIT} (a PIN "ciphertext" is the
 * base64 of the PIN, its HMAC {@code hmac(<walletId>:<pin>)}).
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "spring.data.redis.timeout=3s",
    "mw.auth.seed-api.enabled=true"})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, RedisInfrastructure.class})
class TestAccountIT {

  static final String PIN = "13579";
  static RSAKey jwtKey;

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;

  @MockitoBean EventPublisher publisher;
  @MockitoBean CryptoClient crypto;

  String msisdn;
  Device device;

  @Autowired
  TestAccountIT(MockMvcTester mvc, JdbcTemplate jdbc, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.redis = redis;
  }

  @BeforeAll
  static void keys() throws Exception {
    jwtKey = LoginIT.rsa("jwt-sign.v1", KeyUse.SIGNATURE);
  }

  @BeforeEach
  void setUp() throws Exception {
    when(crypto.tokeniseMsisdn(anyString()))
        .thenAnswer(inv -> new CryptoClient.MsisdnToken("tok-" + inv.getArgument(0), "tokenise-msisdn.v1"));
    when(crypto.verifyPin(anyString(), any(), anyString(), anyString())).thenAnswer(inv -> new CryptoPinVerified(
        LoginIT.hmac(inv.getArgument(0), LoginIT.clear(inv.getArgument(1))).equals(inv.getArgument(2)), null));
    when(crypto.derivePin(anyString(), any(), any())).thenAnswer(inv -> {
      String pin = LoginIT.clear(inv.getArgument(1));
      return pin.chars().distinct().count() == 1
          ? new CryptoPinDerived(true, "REPEATED_DIGIT", null, null)
          : new CryptoPinDerived(false, null, LoginIT.hmac(inv.getArgument(0), pin), "pin-hmac.v1");
    });
    when(crypto.signJws(eq("JWT_SIGN"), eq("RS256"), any(), any())).thenAnswer(inv -> {
      Map<String, Object> claims = inv.getArgument(3);
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
          .type(new JOSEObjectType("at+jwt")).keyID(jwtKey.getKeyID()).build(), JWTClaimsSet.parse(claims));
      jwt.sign(new RSASSASigner(jwtKey));
      return new CryptoJwsSigned(jwt.serialize(), jwtKey.getKeyID(), "RS256");
    });
    when(crypto.jwks("JWT_SIGN")).thenAnswer(inv -> new JWKSet(jwtKey.toPublicJWK()).toJSONObject());
    when(crypto.decrypt(any())).thenAnswer(inv -> msisdn.getBytes(StandardCharsets.UTF_8));

    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'tokenise-msisdn.v1', '{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AA==\","
        + "\"ciphertext\":\"Yw==\",\"aad\":\"aWQ=\"}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", msisdn, "tok-" + msisdn);
    jdbc.update("insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values (?, ?, 'pin-hmac.v1', now())",
        msisdn, LoginIT.hmac(msisdn, PIN));
    device = Device.create();
    jdbc.update("insert into device (device_id, wallet_id, public_key, bound_at) values (?, ?, ?, ?)",
        device.id(), msisdn, device.publicKey(), Timestamp.from(Instant.now()));
  }

  MvcTestResult login(String pin) throws Exception {
    String path = "/auth/v1/sessions";
    String body = "{\"msisdn\":\"" + msisdn + "\",\"encryptedPin\":" + LoginIT.pinJson(pin) + "}";
    Signed s = DeviceBindingIT.sign(device, "POST", path, body);
    return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .header("X-Device-Id", s.deviceId()).header("X-Timestamp", s.timestamp()).header("X-Nonce", s.nonce())
        .header("X-Signature", s.signature()).exchange();
  }

  MvcTestResult account() {
    return mvc.get().uri("/test/v1/accounts/" + msisdn).exchange();
  }

  MvcTestResult post(String action) {
    return mvc.post().uri("/test/v1/accounts/" + msisdn + "/" + action).exchange();
  }

  static void assertCode(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  @Test
  void theAccountShowsThePinTheLockoutAndTheDevice() {
    var r = account();
    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.walletId").isEqualTo(msisdn);
    assertThat(r).bodyJson().extractingPath("$.walletStatus").isEqualTo("ACTIVE");
    assertThat(r).bodyJson().extractingPath("$.pinSet").isEqualTo(true);
    assertThat(r).bodyJson().extractingPath("$.locked").isEqualTo(false);
    assertThat(r).bodyJson().extractingPath("$.failedPinAttempts").isEqualTo(0);
    assertThat(r).bodyJson().extractingPath("$.devices[0].deviceId").isEqualTo(device.id());
    assertThat(r).bodyJson().extractingPath("$.devices[0].status").isEqualTo("BOUND");
    assertThat(r).bodyJson().doesNotHavePath("$.pinLock");
  }

  @Test
  void anUnknownWalletIsNotFound() {
    assertCode(mvc.get().uri("/test/v1/accounts/01700000000").exchange(), 404, "MW-VAL-003");
    assertCode(mvc.post().uri("/test/v1/accounts/01700000000/unlock").exchange(), 404, "MW-VAL-003");
  }

  @Test
  void unlockEndsAPinLockoutAndTheOtpLimits() throws Exception {
    assertCode(login("24680"), 401, "MW-AUTH-040");
    assertCode(login("24680"), 401, "MW-AUTH-040");
    assertThat(account()).bodyJson().extractingPath("$.failedPinAttempts").isEqualTo(2);
    assertThat(login("24680")).hasStatus(423);
    var locked = account();
    assertThat(locked).bodyJson().extractingPath("$.locked").isEqualTo(true);
    assertThat(locked).bodyJson().extractingPath("$.pinLock.level").isEqualTo(1);
    assertThat(locked).bodyJson().hasPathSatisfying("$.lockedUntil", v -> assertThat(v).isNotNull());
    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor, created_at)"
        + " values (?, 'ADMIN', 1, null, 'test', 'ops', now())", msisdn);
    jdbc.update("insert into device_otp (wallet_id, device_id, otp_hash, locale, expires_at, attempts, sends, window_start)"
        + " values (?, ?, null, 'en', now(), 3, 5, now())", msisdn, device.id());
    assertThat(account()).bodyJson().extractingPath("$.adminLocked").isEqualTo(true);
    assertThat(account()).bodyJson().extractingPath("$.deviceOtp.usable").isEqualTo(false);

    var r = post("unlock");

    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.locked").isEqualTo(false);
    assertThat(r).bodyJson().extractingPath("$.adminLocked").isEqualTo(false);
    assertThat(r).bodyJson().extractingPath("$.failedPinAttempts").isEqualTo(0);
    assertThat(r).bodyJson().doesNotHavePath("$.pinLock");
    assertThat(r).bodyJson().doesNotHavePath("$.deviceOtp");
    assertThat(login(PIN)).hasStatus(200);
  }

  @Test
  void unbindEndsTheSessionAndTheRefreshTokens() throws Exception {
    assertThat(login(PIN)).hasStatus(200);
    assertThat(redis.hasKey("sess:" + msisdn + ":" + device.id())).isTrue();

    var r = post("unbind");

    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.devices[0].status").isEqualTo("UNBOUND");
    assertThat(r).bodyJson().hasPathSatisfying("$.devices[0].unboundAt", v -> assertThat(v).isNotNull());
    assertThat(redis.hasKey("sess:" + msisdn + ":" + device.id())).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from refresh_token where wallet_id = ? and not revoked",
        Integer.class, msisdn)).isZero();
    assertCode(login(PIN), 401, "MW-AUTH-047");
  }

  @Test
  void setPinReplacesTheCurrentPin() throws Exception {
    var r = mvc.post().uri("/test/v1/accounts/" + msisdn + "/pin").contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + LoginIT.pinJson("24680") + "}").exchange();

    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.pinSet").isEqualTo(true);
    assertThat(login("24680")).hasStatus(200);
    assertCode(login(PIN), 401, "MW-AUTH-040");
  }

  @Test
  void setPinGivesAWalletWithoutOneItsFirstPin() throws Exception {
    jdbc.update("delete from credential where wallet_id = ?", msisdn);
    assertThat(account()).bodyJson().extractingPath("$.pinSet").isEqualTo(false);

    assertThat(mvc.post().uri("/test/v1/accounts/" + msisdn + "/pin").contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + LoginIT.pinJson(PIN) + "}").exchange()).hasStatus(200);
    assertThat(login(PIN)).hasStatus(200);
  }

  @Test
  void aWeakPinIsRefused() {
    assertCode(mvc.post().uri("/test/v1/accounts/" + msisdn + "/pin").contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":" + LoginIT.pinJson("11111") + "}").exchange(), 400, "MW-AUTH-044");
  }

  @Test
  void theAccountNeverCarriesPinMaterial() throws Exception {
    String body = account().getResponse().getContentAsString();
    assertThat(body).doesNotContain(LoginIT.hmac(msisdn, PIN), "pin_hmac", "pinHmac", "otpHash");
  }
}
