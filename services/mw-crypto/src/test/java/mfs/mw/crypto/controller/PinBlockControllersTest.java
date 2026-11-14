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
import mfs.mw.crypto.config.SecurityConfig;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.repository.KeyRepository;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.security.InternalTokens;
import mfs.mw.crypto.service.HsmKeys;
import mfs.mw.crypto.service.KeyPurpose;
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
 * {@code /internal/v1/pinblock/translate} and {@code /pvv/{generate,verify}} (ticket 64) through the
 * real service and caller policy over a mocked payShield provider: what reaches the provider (key
 * blobs, formats, account), the answers, the key and format checks before any HSM call, the deny by
 * default policy (no caller is bound in application.yml; this test binds a test-only client {@code
 * mw-atm-it}) and fail closed.
 */
@WebMvcTest(
    controllers = {PinBlockController.class, PvvController.class},
    properties = {"spring.test.mockmvc.print=none",
        "mw.internal-auth.allowed-clients=mw-admin-api,mw-auth,mw-audit-dw,mw-atm-it",
        "mw.crypto.authz.purposes.TPK=mw-atm-it", "mw.crypto.authz.purposes.ZPK=mw-atm-it",
        "mw.crypto.authz.purposes.ZPK_AES=mw-atm-it", "mw.crypto.authz.purposes.PVK=mw-atm-it,mw-auth"})
@Import({SecurityConfig.class, CallerPolicy.class, HsmKeys.class, PinBlockService.class,
    PinBlockControllersTest.Beans.class})
class PinBlockControllersTest {

  static final String ATM = "mw-atm-it";
  static final String TPK_BLOB = "U" + "1".repeat(32);
  static final String ZPK_BLOB = "U" + "2".repeat(32);
  static final String ZPK_AES_BLOB = "S" + "3".repeat(32);
  static final String PVK_BLOB = "U" + "4".repeat(32);
  static final String ACCOUNT = "4000001234562000";

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(OperationAuthzProperties.class)
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
  PinBlockControllersTest(MockMvcTester mvc, PayShieldProvider hsm) {
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
    key("ZPK_AES", ZPK_AES_BLOB);
    key("PVK", PVK_BLOB);
    when(repo.findVersions(anyString()))
        .thenAnswer(i -> rows.stream().filter(k -> k.keyId().equals(i.getArgument(0))).toList());
  }

  MvcTestResult post(String client, String path, String json) {
    return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer(client))
        .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
  }

  static String translate(String src, String srcFmt, String block, String dst, String dstFmt) {
    return "{\"source\":{\"keyId\":\"" + src + "\",\"format\":\"" + srcFmt + "\",\"block\":\"" + block + "\"},"
        + "\"destination\":{\"keyId\":\"" + dst + "\",\"format\":\"" + dstFmt + "\"},\"account\":\"" + ACCOUNT + "\"}";
  }

  static String pvv(String path, String extra) {
    return "{\"pvkId\":\"pvk\",\"pvki\":1,\"pinBlock\":{\"keyId\":\"tpk\",\"format\":\"ISO0\",\"block\":\""
        + "0123456789abcdef\"},\"account\":\"" + ACCOUNT + "\"" + extra + "}";
  }

  static void error(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  @Test
  void translatesTpkToZpk() {
    when(hsm.translate(any(), anyString(), any(), any(), any(), anyString())).thenReturn("FEDCBA9876543210");
    MvcTestResult r = post(ATM, "/internal/v1/pinblock/translate",
        translate("tpk", "ISO0", "0123456789abcdef", "zpk", "ISO0"));
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.block").isEqualTo("FEDCBA9876543210");
    assertThat(r).bodyJson().extractingPath("$.keyId").isEqualTo("zpk.v1");
    assertThat(r).bodyJson().extractingPath("$.format").isEqualTo("ISO0");
    verify(hsm).translate(new PinKey(TPK_BLOB, true), "0123456789ABCDEF", PinBlockFormat.ISO0,
        new PinKey(ZPK_BLOB, false), PinBlockFormat.ISO0, ACCOUNT);
  }

  @Test
  void translatesToAnAesZpkInFormat4() {
    when(hsm.translate(any(), anyString(), any(), any(), any(), anyString())).thenReturn("A".repeat(32));
    assertThat(post(ATM, "/internal/v1/pinblock/translate",
        translate("zpk", "ISO3", "0123456789ABCDEF", "zpk-aes", "ISO4"))).hasStatusOk();
    verify(hsm).translate(new PinKey(ZPK_BLOB, false), "0123456789ABCDEF", PinBlockFormat.ISO3,
        new PinKey(ZPK_AES_BLOB, false), PinBlockFormat.ISO4, ACCOUNT);
  }

  @Test
  void keyAndFormatChecksComeBeforeTheHsm() {
    // format 4 needs an AES key; a TDES key cannot take it
    error(post(ATM, "/internal/v1/pinblock/translate", translate("tpk", "ISO4", "0".repeat(32), "zpk", "ISO0")),
        400, "MW-VAL-015");
    // the destination is a zone key
    error(post(ATM, "/internal/v1/pinblock/translate", translate("tpk", "ISO0", "0".repeat(16), "tpk", "ISO0")),
        400, "MW-VAL-015");
    // the block length fits the format
    error(post(ATM, "/internal/v1/pinblock/translate", translate("tpk", "ISO0", "0".repeat(32), "zpk", "ISO0")),
        400, "MW-VAL-001");
    // a non-PIN key
    error(post(ATM, "/internal/v1/pinblock/translate", translate("pvk", "ISO0", "0".repeat(16), "zpk", "ISO0")),
        400, "MW-VAL-015");
    error(post(ATM, "/internal/v1/pinblock/translate", translate("nope", "ISO0", "0".repeat(16), "zpk", "ISO0")),
        404, "MW-VAL-010");
    verify(hsm, never()).translate(any(), anyString(), any(), any(), any(), anyString());
  }

  @Test
  void pvvGenerateAndVerify() {
    when(hsm.generatePvv(anyString(), any(), anyString(), any(), anyString(), anyInt())).thenReturn("4821");
    MvcTestResult g = post(ATM, "/internal/v1/pvv/generate", pvv("generate", ""));
    assertThat(g).hasStatusOk();
    assertThat(g).bodyJson().extractingPath("$.pvv").isEqualTo("4821");
    assertThat(g).bodyJson().extractingPath("$.pvkId").isEqualTo("pvk.v1");
    verify(hsm).generatePvv(PVK_BLOB, new PinKey(TPK_BLOB, true), "0123456789ABCDEF", PinBlockFormat.ISO0, ACCOUNT, 1);

    when(hsm.verifyPvv(anyString(), any(), anyString(), any(), anyString(), anyInt(), eq("4821"))).thenReturn(true);
    when(hsm.verifyPvv(anyString(), any(), anyString(), any(), anyString(), anyInt(), eq("4822"))).thenReturn(false);
    MvcTestResult ok = post(ATM, "/internal/v1/pvv/verify", pvv("verify", ",\"pvv\":\"4821\""));
    assertThat(ok).hasStatusOk();
    assertThat(ok).bodyJson().extractingPath("$.match").isEqualTo(true);
    assertThat(post(ATM, "/internal/v1/pvv/verify", pvv("verify", ",\"pvv\":\"4822\"")))
        .bodyJson().extractingPath("$.match").isEqualTo(false);
    error(post(ATM, "/internal/v1/pvv/verify", pvv("verify", ",\"pvv\":\"48a2\"")), 400, "MW-VAL-001");
  }

  @Test
  void denyByDefaultPerKeyPurpose() {
    // mw-admin-api is an allowed caller of the API but bound to none of these purposes
    error(post("mw-admin-api", "/internal/v1/pinblock/translate",
        translate("tpk", "ISO0", "0".repeat(16), "zpk", "ISO0")), 403, "MW-AUTH-002");
    // mw-auth may use the PVK here but not the TPK
    error(post("mw-auth", "/internal/v1/pvv/generate", pvv("generate", "")), 403, "MW-AUTH-002");
    verify(hsm, never()).translate(any(), anyString(), any(), any(), any(), anyString());
    verify(hsm, never()).generatePvv(anyString(), any(), anyString(), any(), anyString(), anyInt());
  }

  @Test
  void anHsmOutageFailsClosed() {
    when(hsm.translate(any(), anyString(), any(), any(), any(), anyString()))
        .thenThrow(CryptoErrors.hsmUnavailable("pinblock-translate", null));
    error(post(ATM, "/internal/v1/pinblock/translate", translate("tpk", "ISO0", "0".repeat(16), "zpk", "ISO0")),
        503, "MW-SYS-010");
  }
}
