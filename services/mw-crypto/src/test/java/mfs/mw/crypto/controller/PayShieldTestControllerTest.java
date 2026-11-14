package mfs.mw.crypto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.client.PayShieldProvider;
import mfs.mw.crypto.client.PinBlockFormat;
import mfs.mw.crypto.client.PinBlockProvider.PinKey;
import mfs.mw.crypto.config.OperationAuthzProperties;
import mfs.mw.crypto.config.PayShieldProperties;
import mfs.mw.crypto.config.SecurityConfig;
import mfs.mw.crypto.config.TestApiProperties;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.repository.KeyRepository;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.security.InternalTokens;
import mfs.mw.crypto.service.HsmKeys;
import mfs.mw.crypto.service.KeyPurpose;
import mfs.mw.crypto.service.PayShieldTestService;
import mfs.mw.crypto.service.PinBlockService;
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
 * The dev-only payShield test API (ticket 76, {@code mw.crypto.test-api.enabled}): the LMK blob of a
 * TPK/ZPK for the simulator's {@code /sim/payshield/pinblock}, and PVV generate / verify run as the
 * test-only client {@code mw-e2e-crypto}, through the real caller policy (the dev profile binds that
 * client to TPK and PVK only), over a mocked payShield provider.
 */
@WebMvcTest(
    controllers = PayShieldTestController.class,
    properties = {"spring.test.mockmvc.print=none", "mw.crypto.test-api.enabled=true",
        "mw.crypto.authz.purposes.TPK=mw-e2e-crypto", "mw.crypto.authz.purposes.PVK=mw-e2e-crypto,mw-auth",
        "mw.crypto.authz.purposes.ZPK=mw-auth"})
@Import({SecurityConfig.class, CallerPolicy.class, HsmKeys.class, PinBlockService.class, PayShieldTestService.class,
    PayShieldTestControllerTest.Beans.class})
class PayShieldTestControllerTest {

  static final String TPK_BLOB = "U" + "1".repeat(32);
  static final String ZPK_BLOB = "U" + "2".repeat(32);
  static final String PVK_BLOB = "U" + "4".repeat(32);
  static final String ACCOUNT = "4000001234562000";

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties({OperationAuthzProperties.class, PayShieldProperties.class, TestApiProperties.class})
  static class Beans {
    @Bean
    PayShieldProvider payShieldProvider() {
      PayShieldProvider p = mock(PayShieldProvider.class);
      when(p.name()).thenReturn("payshield");
      when(p.capabilities()).thenReturn(EnumSet.of(Capability.PIN_BLOCK, Capability.PVV));
      return p;
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
  final PayShieldProvider hsm;
  @MockitoBean KeyRepository repo;
  final List<CryptoKey> rows = new ArrayList<>();

  @Autowired
  PayShieldTestControllerTest(MockMvcTester mvc, PayShieldProvider hsm) {
    this.mvc = mvc;
    this.hsm = hsm;
  }

  void key(String purpose, String blob) {
    KeyPurpose p = KeyPurpose.parse(purpose);
    rows.add(new CryptoKey(p.defaultKeyId(), 1, purpose, p.algorithm().name(), "payshield", blob, KeyState.ACTIVE,
        Instant.now(), null));
  }

  @BeforeEach
  void keys() {
    org.mockito.Mockito.reset(hsm);
    when(hsm.name()).thenReturn("payshield");
    when(hsm.capabilities()).thenReturn(EnumSet.of(Capability.PIN_BLOCK, Capability.PVV));
    key("TPK", TPK_BLOB);
    key("ZPK", ZPK_BLOB);
    key("PVK", PVK_BLOB);
    when(repo.findVersions(anyString()))
        .thenAnswer(i -> rows.stream().filter(k -> k.keyId().equals(i.getArgument(0))).toList());
    when(repo.findByPurpose(anyString()))
        .thenAnswer(i -> rows.stream().filter(k -> k.purpose().equals(i.getArgument(0))).toList());
  }

  MvcTestResult post(String path, String json) {
    return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
  }

  static String pvv(String key, String extra) {
    return "{\"pvkId\":\"pvk\",\"pvki\":1,\"pinBlock\":{\"keyId\":\"" + key + "\",\"format\":\"ISO0\",\"block\":\""
        + "0123456789abcdef\"},\"account\":\"" + ACCOUNT + "\"" + extra + "}";
  }

  static void error(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  @Test
  void servesTheLmkBlobOfTheActivePinKeyOfAPurposeWithoutAToken() {
    MvcTestResult r = mvc.get().uri("/test/v1/payshield/keys/TPK").exchange();
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.keyId").isEqualTo("tpk");
    assertThat(r).bodyJson().extractingPath("$.kid").isEqualTo("tpk.v1");
    assertThat(r).bodyJson().extractingPath("$.purpose").isEqualTo("TPK");
    assertThat(r).bodyJson().extractingPath("$.keyBlock").isEqualTo(TPK_BLOB);
  }

  @Test
  void neverServesAPvkOrAKeyOfAnotherKind() {
    // the PVK pair stays inside: a PVV is only ever made and checked by the HSM
    error(mvc.get().uri("/test/v1/payshield/keys/PVK").exchange(), 400, "MW-VAL-015");
    error(mvc.get().uri("/test/v1/payshield/keys/JWT_SIGN").exchange(), 400, "MW-VAL-015");
    error(mvc.get().uri("/test/v1/payshield/keys/NOPE").exchange(), 400, "MW-VAL-011");
  }

  @Test
  void pvvGenerateAndVerifyRunAsTheTestClient() {
    when(hsm.generatePvv(anyString(), any(), anyString(), any(), anyString(), anyInt())).thenReturn("4821");
    MvcTestResult g = post("/test/v1/payshield/pvv/generate", pvv("tpk", ""));
    assertThat(g).hasStatusOk();
    assertThat(g).bodyJson().extractingPath("$.pvv").isEqualTo("4821");
    assertThat(g).bodyJson().extractingPath("$.pvkId").isEqualTo("pvk.v1");
    verify(hsm).generatePvv(PVK_BLOB, new PinKey(TPK_BLOB, true), "0123456789ABCDEF", PinBlockFormat.ISO0, ACCOUNT, 1);

    when(hsm.verifyPvv(anyString(), any(), anyString(), any(), anyString(), anyInt(), eq("4821"))).thenReturn(true);
    when(hsm.verifyPvv(anyString(), any(), anyString(), any(), anyString(), anyInt(), eq("4822"))).thenReturn(false);
    assertThat(post("/test/v1/payshield/pvv/verify", pvv("tpk", ",\"pvv\":\"4821\"")))
        .bodyJson().extractingPath("$.match").isEqualTo(true);
    assertThat(post("/test/v1/payshield/pvv/verify", pvv("tpk", ",\"pvv\":\"4822\"")))
        .bodyJson().extractingPath("$.match").isEqualTo(false);
  }

  @Test
  void theTestClientIsBoundToTpkAndPvkOnly() {
    // a ZPK is not bound to mw-e2e-crypto: the caller policy refuses it before the HSM
    error(post("/test/v1/payshield/pvv/generate", pvv("zpk", "")), 403, "MW-AUTH-002");
    verify(hsm, never()).generatePvv(anyString(), any(), anyString(), any(), anyString(), anyInt());
  }

  @Test
  void aBearerTokenGrantsNothingMore() {
    // the test API acts as its own client whatever token comes along: mw-auth's ZPK binding does not apply
    MvcTestResult r = mvc.post().uri("/test/v1/payshield/pvv/generate")
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-auth"))
        .contentType(MediaType.APPLICATION_JSON).content(pvv("zpk", "")).exchange();
    error(r, 403, "MW-AUTH-002");
  }
}
