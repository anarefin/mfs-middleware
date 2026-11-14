package mfs.mw.crypto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;

import java.security.cert.X509Certificate;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import mfs.mw.crypto.config.SecurityConfig;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.security.InternalTokens;
import mfs.mw.crypto.service.KeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The key API with mTLS on (D24) and the dev key bootstrap off (production until the ceremony):
 * the token's azp must be the client certificate's CN, and nobody may generate a key.
 */
@WebMvcTest(
    controllers = KeyController.class,
    properties = {"spring.test.mockmvc.print=none", "mw.tls.enabled=true", "mw.crypto.bootstrap-keys=false"})
@Import(SecurityConfig.class)
class KeyControllerMtlsTest {

  @DynamicPropertySource
  static void jwks(DynamicPropertyRegistry r) {
    r.add("mw.internal-auth.jwk-set-uri", InternalTokens::jwkSetUri);
  }

  final MockMvcTester mvc;
  @MockitoBean KeyService keys;

  @Autowired
  KeyControllerMtlsTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static X509Certificate cert(String cn) {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=" + cn));
    return cert;
  }

  @Test
  void answersWhenTheCertificateIsTheTokensClient() {
    when(keys.list()).thenReturn(new KeyView.Page(List.of()));
    assertThat(mvc.get().uri("/internal/v1/keys").with(x509(cert("mw-auth")))
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-auth")).exchange()).hasStatusOk();
  }

  @Test
  void refusesATokenOfAnotherClientOrNoCertificate() {
    assertThat(mvc.get().uri("/internal/v1/keys").with(x509(cert("mw-auth")))
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-admin-api")).exchange())
        .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    assertThat(mvc.get().uri("/internal/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-auth")).exchange()).hasStatus(403);
    verify(keys, never()).list();
  }

  @Test
  void generationIsClosedWithoutTheDevBootstrapRotationIsNot() {
    assertThat(mvc.post().uri("/internal/v1/keys").with(x509(cert("mw-admin-api")))
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-admin-api"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"JWT_SIGN\"}").exchange())
        .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    verify(keys, never()).generate(anyString(), any());
    when(keys.rotate("jwt-sign")).thenReturn(new KeyView("jwt-sign", "JWT_SIGN", "RSA_3072", "pkcs11", List.of()));
    assertThat(mvc.post().uri("/internal/v1/keys/jwt-sign/rotate").with(x509(cert("mw-admin-api")))
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-admin-api")).exchange()).hasStatusOk();
  }
}
