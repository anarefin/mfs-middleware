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
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import mfs.mw.auth.RedisInfrastructure;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.dto.EncryptedPin;
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
 * A type whose session limit is n ends the oldest session on the (n+1)th login (ticket 106,
 * CH-APP-04). This DSO allows 2 sessions and 4 devices.
 */
@SpringBootTest(properties = {
    "mw.outbox.relay.enabled=false",
    "spring.data.redis.timeout=3s",
    "mw.auth.seed-api.enabled=true",
    "mw.auth.app-types.types.DSO.max-sessions=2",
    "mw.auth.app-types.types.DSO.max-devices=4"
})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, RedisInfrastructure.class})
class ConcurrentSessionIT {

  static final String PIN = "13579";
  static RSAKey jwtKey;

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;

  @MockitoBean EventPublisher publisher;
  @MockitoBean CryptoClient crypto;

  String msisdn;

  @Autowired
  ConcurrentSessionIT(MockMvcTester mvc, JdbcTemplate jdbc, StringRedisTemplate redis) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.redis = redis;
  }

  @BeforeAll
  static void keys() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    var kp = g.generateKeyPair();
    jwtKey = new RSAKey.Builder((RSAPublicKey) kp.getPublic()).privateKey((RSAPrivateKey) kp.getPrivate())
        .keyID("jwt-sign.v1").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    when(crypto.tokeniseMsisdn(anyString()))
        .thenAnswer(inv -> new CryptoClient.MsisdnToken("tok-" + inv.getArgument(0), "tokenise-msisdn.v1"));
    when(crypto.verifyPin(anyString(), any(), anyString(), anyString())).thenAnswer(inv -> new CryptoPinVerified(
        hmac(inv.getArgument(0), clear(inv.getArgument(1))).equals(inv.getArgument(2)), null));
    when(crypto.signJws(eq("JWT_SIGN"), eq("RS256"), any(), any())).thenAnswer(inv -> {
      Map<String, Object> claims = inv.getArgument(3);
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
          .type(new JOSEObjectType("at+jwt")).keyID(jwtKey.getKeyID()).build(), JWTClaimsSet.parse(claims));
      jwt.sign(new RSASSASigner(jwtKey));
      return new CryptoJwsSigned(jwt.serialize(), jwtKey.getKeyID(), "RS256");
    });
    when(crypto.decrypt(any())).thenAnswer(inv -> msisdn.getBytes(StandardCharsets.UTF_8));

    msisdn = "015" + String.format("%08d", (int) (Math.random() * 100_000_000));
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'tokenise-msisdn.v1', '{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AA==\","
        + "\"ciphertext\":\"Yw==\",\"aad\":\"aWQ=\"}'::jsonb, 'DSO', 'ACTIVE', true, 1)", msisdn, "tok-" + msisdn);
    jdbc.update("insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values (?, ?, 'pin-hmac.v1', now())",
        msisdn, hmac(msisdn, PIN));
  }

  @Test
  void theThirdSessionEndsTheOldest() throws Exception {
    Device first = bind();
    Device second = bind();
    Device third = bind();

    MvcTestResult a = login(first);
    assertThat(a).hasStatus(200);
    Thread.sleep(20);
    MvcTestResult b = login(second);
    assertThat(b).hasStatus(200);
    Thread.sleep(20);
    MvcTestResult c = login(third);
    assertThat(c).hasStatus(200);

    assertThat(redis.hasKey("sess:" + msisdn + ":" + first.id())).isFalse();
    assertThat(redis.hasKey("sess:" + msisdn + ":" + second.id())).isTrue();
    assertThat(redis.hasKey("sess:" + msisdn + ":" + third.id())).isTrue();

    String refresh = com.jayway.jsonpath.JsonPath.read(a.getResponse().getContentAsString(), "$.refreshToken");
    assertThat(loginRefresh(first, refresh)).hasStatus(401);
  }

  private Device bind() throws Exception {
    Device d = Device.create();
    jdbc.update("insert into device (device_id, wallet_id, public_key, bound_at) values (?, ?, ?, ?)",
        d.id(), msisdn, d.publicKey(), Timestamp.from(Instant.now()));
    Thread.sleep(5);
    return d;
  }

  private MvcTestResult login(Device d) throws Exception {
    String body = "{\"msisdn\":\"" + msisdn + "\",\"encryptedPin\":" + pinJson() + "}";
    return signed(d, "/auth/v1/sessions", body);
  }

  private MvcTestResult loginRefresh(Device d, String token) throws Exception {
    return signed(d, "/auth/v1/tokens/refresh", "{\"refreshToken\":\"" + token + "\"}");
  }

  private MvcTestResult signed(Device d, String path, String body) throws Exception {
    Signed s = DeviceBindingIT.sign(d, "POST", path, body);
    return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .header("X-Device-Id", s.deviceId()).header("X-Timestamp", s.timestamp()).header("X-Nonce", s.nonce())
        .header("X-Signature", s.signature()).exchange();
  }

  private static String pinJson() {
    EncryptedPin p = new EncryptedPin("pin-transport.v2",
        Base64.getEncoder().encodeToString(PIN.getBytes(StandardCharsets.UTF_8)));
    return "{\"keyId\":\"" + p.keyId() + "\",\"ciphertext\":\"" + p.ciphertext() + "\"}";
  }

  private static String hmac(String walletId, String pin) {
    return Base64.getEncoder().encodeToString(("hmac(" + walletId + ":" + pin + ")").getBytes(StandardCharsets.UTF_8));
  }

  private static String clear(EncryptedPin p) {
    return new String(Base64.getDecoder().decode(p.ciphertext()), StandardCharsets.UTF_8);
  }
}
