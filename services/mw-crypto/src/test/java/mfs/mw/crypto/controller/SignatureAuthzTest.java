package mfs.mw.crypto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import mfs.mw.crypto.client.FakeCryptoProvider;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.config.OperationAuthzProperties;
import mfs.mw.crypto.config.SecurityConfig;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.repository.KeyRepository;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.security.InternalTokens;
import mfs.mw.crypto.service.HsmKeys;
import mfs.mw.crypto.service.HttpSignatureService;
import mfs.mw.crypto.service.KeyPurpose;
import mfs.mw.crypto.service.XmlDsigService;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
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

/**
 * Per-operation authorisation of the ticket 65 endpoints (security/CallerPolicy): with the shipped
 * {@code mw.crypto.authz} nobody may sign or verify XML-DSig or HTTP signatures (no adapter is bound
 * yet), whatever the key is named by; binding a purpose to a client (here HTTP_SIGN_EC to
 * mw-admin-api, a test-only override) opens it to that client only. A partner PEM is bound by its
 * key type.
 */
@WebMvcTest(
    controllers = {XmlDsigController.class, HttpSignatureController.class},
    properties = {"spring.test.mockmvc.print=none", "mw.crypto.authz.purposes.HTTP_SIGN_EC=mw-admin-api"})
@Import({SecurityConfig.class, CallerPolicy.class, HsmKeys.class, XmlDsigService.class, HttpSignatureService.class,
    SignatureAuthzTest.Beans.class})
class SignatureAuthzTest {

  static final List<String> CLIENTS = List.of("mw-auth", "mw-audit-dw", "mw-admin-api");
  static final String XML = Base64.getEncoder().encodeToString("<d>1</d>".getBytes(StandardCharsets.UTF_8));
  static final String COMPONENTS = "[{\"name\":\"@method\",\"value\":\"POST\"}]";

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
  SignatureAuthzTest(MockMvcTester mvc, FakeCryptoProvider hsm) {
    this.mvc = mvc;
    this.hsm = hsm;
  }

  @BeforeEach
  void keys() {
    for (String purpose : List.of("XMLDSIG", "HTTP_SIGN_RSA", "HTTP_SIGN_EC", "HMAC_PARTNER")) {
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

  static String pem(Object key) throws Exception {
    StringWriter out = new StringWriter();
    try (JcaPEMWriter w = new JcaPEMWriter(out)) {
      w.writeObject(key);
    }
    return out.toString().replace("\n", "\\n");
  }

  static String httpVerify(String keyField) {
    return "{" + keyField + ",\"signatureInput\":\"sig1=(\\\"@method\\\")\",\"signature\":\"sig1=:AAAA:\",\"components\":"
        + COMPONENTS + "}";
  }

  @Test
  void xmlDsigIsDeniedToEveryCallerByDefault() throws Exception {
    String rsaPem = pem(((KeyPair) hsm.key("http-sign-rsa.v1")).getPublic());
    for (String client : CLIENTS) {
      forbidden(post(client, "/internal/v1/xmldsig/sign", "{\"xml\":\"" + XML + "\"}"));
      forbidden(post(client, "/internal/v1/xmldsig/sign", "{\"keyId\":\"xmldsig\",\"xml\":\"" + XML + "\"}"));
      forbidden(post(client, "/internal/v1/xmldsig/verify", "{\"keyId\":\"xmldsig\",\"xml\":\"" + XML + "\"}"));
      forbidden(post(client, "/internal/v1/xmldsig/verify", "{\"certificatePem\":\"" + rsaPem + "\",\"xml\":\"" + XML + "\"}"));
    }
  }

  @Test
  void httpSignaturesAreDeniedUnlessThePurposeIsBoundToTheCaller() throws Exception {
    String sign = "{\"keyId\":\"%s\",\"components\":" + COMPONENTS + "}";
    for (String client : CLIENTS) {
      forbidden(post(client, "/internal/v1/http-signature/sign", sign.formatted("http-sign-rsa")));
      forbidden(post(client, "/internal/v1/http-signature/sign", sign.formatted("hmac-partner")));
      forbidden(post(client, "/internal/v1/http-signature/verify", httpVerify("\"keyId\":\"hmac-partner\"")));
      forbidden(post(client, "/internal/v1/http-signature/verify",
          httpVerify("\"certificatePem\":\"" + pem(((KeyPair) hsm.key("http-sign-rsa.v1")).getPublic()) + "\"")));
    }
    String ecPem = pem(((KeyPair) hsm.key("http-sign-ec.v1")).getPublic());
    assertThat(post("mw-admin-api", "/internal/v1/http-signature/sign", sign.formatted("http-sign-ec"))).hasStatusOk();
    assertThat(post("mw-admin-api", "/internal/v1/http-signature/verify", httpVerify("\"keyId\":\"http-sign-ec\"")))
        .hasStatusOk().bodyJson().extractingPath("$.valid").isEqualTo(false);
    assertThat(post("mw-admin-api", "/internal/v1/http-signature/verify", httpVerify("\"certificatePem\":\"" + ecPem + "\"")))
        .hasStatusOk();
    forbidden(post("mw-auth", "/internal/v1/http-signature/sign", sign.formatted("http-sign-ec")));
    forbidden(post("mw-auth", "/internal/v1/http-signature/verify", httpVerify("\"certificatePem\":\"" + ecPem + "\"")));
  }
}
