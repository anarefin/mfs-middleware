package mfs.mw.crypto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.spec.MGF1ParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.crypto.client.FakeCryptoProvider;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.config.OperationAuthzProperties;
import mfs.mw.crypto.config.SecurityConfig;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.mapper.KeyMapper;
import mfs.mw.crypto.repository.KeyRepository;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.security.InternalTokens;
import mfs.mw.crypto.service.EnvelopeService;
import mfs.mw.crypto.service.HmacService;
import mfs.mw.crypto.service.HsmKeys;
import mfs.mw.crypto.service.JwsService;
import mfs.mw.crypto.service.KeyPurpose;
import mfs.mw.crypto.service.KeyService;
import mfs.mw.crypto.service.PinService;
import mfs.mw.crypto.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-operation authorisation (security/CallerPolicy, {@code mw.crypto.authz} of application.yml):
 * the real controllers and services over an in-memory provider; for every operation a caller that
 * is not bound to it gets 403 MW-AUTH-002 and the bound caller gets its answer.
 */
@WebMvcTest(
    controllers = {PinController.class, JwsController.class, HmacController.class, TokenController.class,
        EnvelopeController.class},
    properties = {"spring.test.mockmvc.print=none", "mw.crypto.authz.purposes.HMAC_WEBHOOK=mw-admin-api"})
@Import({SecurityConfig.class, CallerPolicy.class, HsmKeys.class, PinService.class, JwsService.class,
    HmacService.class, TokenService.class, EnvelopeService.class, KeyService.class, KeyMapper.class,
    OperationAuthzTest.Beans.class})
class OperationAuthzTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String AUTH = "mw-auth";
  static final String AUDIT = "mw-audit-dw";
  static final String ADMIN = "mw-admin-api";

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties({OperationAuthzProperties.class, CryptoProperties.class})
  static class Beans {
    @Bean
    FakeCryptoProvider fakeCryptoProvider() {
      return new FakeCryptoProvider();
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @DynamicPropertySource
  static void jwks(DynamicPropertyRegistry r) {
    r.add("mw.internal-auth.jwk-set-uri", InternalTokens::jwkSetUri);
  }

  final MockMvcTester mvc;
  final FakeCryptoProvider hsm;
  @MockitoBean KeyRepository repo;
  final List<CryptoKey> rows = new ArrayList<>();

  @Autowired
  OperationAuthzTest(MockMvcTester mvc, FakeCryptoProvider hsm) {
    this.mvc = mvc;
    this.hsm = hsm;
  }

  @BeforeEach
  void keys() {
    for (String purpose : List.of("PIN_TRANSPORT", "PIN_HMAC", "JWT_SIGN", "ASSERTION_SIGN", "AUDIT_ANCHOR_SIGN",
        "TOKENISE_MSISDN", "TOKENISE_ACCOUNT", "KEK_COLUMN", "KEK_FILE", "HMAC_WEBHOOK")) {
      KeyPurpose p = KeyPurpose.parse(purpose);
      String keyId = p.defaultKeyId();
      rows.add(new CryptoKey(keyId, 1, purpose, p.algorithm().name(), "pkcs11", keyId + ".v1", KeyState.ACTIVE,
          Instant.now(), null));
      hsm.generateKey(keyId + ".v1", p.algorithm());
    }
    when(repo.findVersions(anyString()))
        .thenAnswer(i -> rows.stream().filter(k -> k.keyId().equals(i.getArgument(0))).toList());
    when(repo.findByPurpose(anyString()))
        .thenAnswer(i -> rows.stream().filter(k -> k.purpose().equals(i.getArgument(0))).toList());
  }

  MvcTestResult post(String client, String path, String json) {
    return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer(client))
        .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
  }

  static void forbidden(MvcTestResult r) {
    assertThat(r).hasStatus(403);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
  }

  String pinBlock() throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
    c.init(Cipher.ENCRYPT_MODE, hsm.publicKey("pin-transport.v1"),
        new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
    return "{\"keyId\":\"pin-transport\",\"ciphertext\":\"" + Base64.getEncoder().encodeToString(c.doFinal("40973".getBytes()))
        + "\"}";
  }

  @Test
  void pinOperationsAreForMwAuthOnly() throws Exception {
    String derive = "{\"walletId\":\"w-1\",\"encryptedPin\":" + pinBlock() + "}";
    MvcTestResult ok = post(AUTH, "/internal/v1/pin/derive", derive);
    assertThat(ok).hasStatusOk();
    String hmac = JSON.readTree(ok.getResponse().getContentAsString()).get("hmac").asString();
    forbidden(post(AUDIT, "/internal/v1/pin/derive", derive));
    forbidden(post(ADMIN, "/internal/v1/pin/derive", derive));

    String verify = "{\"walletId\":\"w-1\",\"encryptedPin\":" + pinBlock() + ",\"storedHmac\":\"" + hmac
        + "\",\"hmacKeyId\":\"pin-hmac.v1\"}";
    assertThat(post(AUTH, "/internal/v1/pin/verify", verify)).hasStatusOk().bodyJson().extractingPath("$.match")
        .isEqualTo(true);
    forbidden(post(AUDIT, "/internal/v1/pin/verify", verify));
  }

  @Test
  void jwsSigningIsBoundPerPurposeAlsoByKeyId() {
    String jwt = "{\"purpose\":\"JWT_SIGN\",\"payload\":{\"sub\":\"w-1\"}}";
    assertThat(post(AUTH, "/internal/v1/jws/sign", jwt)).hasStatusOk();
    forbidden(post(AUDIT, "/internal/v1/jws/sign", jwt));
    forbidden(post(ADMIN, "/internal/v1/jws/sign", jwt));
    forbidden(post(AUDIT, "/internal/v1/jws/sign", "{\"keyId\":\"jwt-sign\",\"payload\":{}}"));
    assertThat(post(AUTH, "/internal/v1/jws/sign", "{\"keyId\":\"assertion-sign\",\"payload\":{}}")).hasStatusOk();

    String anchor = "{\"purpose\":\"AUDIT_ANCHOR_SIGN\",\"payload\":{\"root\":\"r\"}}";
    assertThat(post(AUDIT, "/internal/v1/jws/sign", anchor)).hasStatusOk();
    forbidden(post(AUTH, "/internal/v1/jws/sign", anchor));
    forbidden(post(AUTH, "/internal/v1/jws/sign", "{\"keyId\":\"audit-anchor-sign.v1\",\"payload\":{}}"));

    // public keys stay open to every allowed caller
    assertThat(mvc.get().uri("/internal/v1/jwks/JWT_SIGN").header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer(ADMIN))
        .exchange()).hasStatusOk();
  }

  @Test
  void tokensEnvelopesAndHmacAreBoundPerPurpose() throws Exception {
    String msisdn = "{\"domain\":\"MSISDN\",\"values\":[\"01712345678\"]}";
    assertThat(post(AUTH, "/internal/v1/tokens", msisdn)).hasStatusOk();
    assertThat(post(AUDIT, "/internal/v1/tokens", msisdn)).hasStatusOk();
    forbidden(post(ADMIN, "/internal/v1/tokens", msisdn));
    forbidden(post(AUTH, "/internal/v1/tokens", "{\"domain\":\"ACCOUNT\",\"values\":[\"1\"]}"));

    MvcTestResult sealed = post(AUTH, "/internal/v1/envelope/encrypt", "{\"purpose\":\"KEK_COLUMN\",\"plaintext\":\"AAAA\"}");
    assertThat(sealed).hasStatusOk();
    forbidden(post(AUDIT, "/internal/v1/envelope/encrypt", "{\"purpose\":\"KEK_COLUMN\",\"plaintext\":\"AAAA\"}"));
    forbidden(post(AUDIT, "/internal/v1/envelope/encrypt", "{\"kekId\":\"kek-column\",\"plaintext\":\"AAAA\"}"));
    forbidden(post(AUTH, "/internal/v1/envelope/encrypt", "{\"purpose\":\"KEK_FILE\",\"plaintext\":\"AAAA\"}"));
    String envelope = sealed.getResponse().getContentAsString();
    assertThat(post(AUTH, "/internal/v1/envelope/decrypt", envelope)).hasStatusOk();
    forbidden(post(AUDIT, "/internal/v1/envelope/decrypt", envelope));

    String sign = "{\"keyId\":\"hmac-webhook\",\"data\":\"AAAA\"}";
    MvcTestResult mac = post(ADMIN, "/internal/v1/hmac/sign", sign);
    assertThat(mac).hasStatusOk();
    forbidden(post(AUTH, "/internal/v1/hmac/sign", sign));
    JsonNode m = JSON.readTree(mac.getResponse().getContentAsString());
    forbidden(post(AUTH, "/internal/v1/hmac/verify",
        "{\"keyId\":\"hmac-webhook\",\"data\":\"AAAA\",\"mac\":\"" + m.get("mac").asString() + "\"}"));
  }
}
