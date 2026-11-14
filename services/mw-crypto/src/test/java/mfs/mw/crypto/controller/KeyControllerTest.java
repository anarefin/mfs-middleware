package mfs.mw.crypto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.crypto.config.SecurityConfig;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.dto.PublicKeyView;
import mfs.mw.crypto.exception.CryptoErrors;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The key API (crypto-internal.v1): mapping, validation, the 5.3 envelope and the internal service
 * JWT in front of it (signature, issuer, audience mw-crypto, azp allow-list, key-admin clients for
 * generate and rotate). {@link KeyControllerMtlsTest}: mTLS on, generation off.
 */
@WebMvcTest(
    controllers = KeyController.class,
    properties = {"spring.test.mockmvc.print=none", "mw.crypto.bootstrap-keys=true"})
@Import(SecurityConfig.class)
class KeyControllerTest {

  static final String ADMIN = "mw-admin-api";
  static final Instant T0 = Instant.parse("2026-11-14T08:00:00Z");

  @DynamicPropertySource
  static void jwks(DynamicPropertyRegistry r) {
    r.add("mw.internal-auth.jwk-set-uri", InternalTokens::jwkSetUri);
  }

  final MockMvcTester mvc;
  @MockitoBean KeyService keys;

  @Autowired
  KeyControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static KeyView jwtSign() {
    return new KeyView("jwt-sign", "JWT_SIGN", "RSA_3072", "pkcs11",
        List.of(new KeyView.Version(1, "jwt-sign.v1", "ACTIVE", T0, null)));
  }

  MvcTestResult get(String path, String token) {
    return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
  }

  @Test
  void listsTheKeys() {
    when(keys.list()).thenReturn(new KeyView.Page(List.of(jwtSign())));
    MvcTestResult r = get("/internal/v1/keys", InternalTokens.forClient(ADMIN));
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.items[0].keyId").isEqualTo("jwt-sign");
    assertThat(r).bodyJson().extractingPath("$.items[0].versions[0].kid").isEqualTo("jwt-sign.v1");
  }

  @Test
  void generatesAKey() {
    when(keys.generate("JWT_SIGN", null)).thenReturn(jwtSign());
    MvcTestResult r = mvc.post().uri("/internal/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer(ADMIN))
        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"JWT_SIGN\"}").exchange();
    assertThat(r).hasStatus(201);
    assertThat(r).bodyJson().extractingPath("$.keyId").isEqualTo("jwt-sign");
  }

  @Test
  void refusesABadPurposeOrKeyIdBeforeTheService() {
    MvcTestResult r = mvc.post().uri("/internal/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer(ADMIN))
        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"jwt sign\",\"keyId\":\"Bad_Id\"}").exchange();
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(keys, never()).generate(anyString(), any());
  }

  @Test
  void rotatesAndReturnsThePublicKey() {
    when(keys.rotate("jwt-sign")).thenReturn(jwtSign());
    assertThat(mvc.post().uri("/internal/v1/keys/jwt-sign/rotate")
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer(ADMIN)).exchange()).hasStatusOk();
    when(keys.publicKeys("jwt-sign")).thenReturn(new PublicKeyView("jwt-sign", "JWT_SIGN", "RSA_3072",
        List.of(new PublicKeyView.Version(1, "jwt-sign.v1", "ACTIVE", "-----BEGIN PUBLIC KEY-----\n",
            Map.of("kty", "RSA", "kid", "jwt-sign.v1"))),
        Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "jwt-sign.v1")))));
    MvcTestResult r = get("/internal/v1/keys/jwt-sign/public", InternalTokens.forClient(ADMIN));
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.jwks.keys[0].kid").isEqualTo("jwt-sign.v1");
  }

  @Test
  void serviceFailuresAreTheEnvelope() {
    when(keys.publicKeys("nope")).thenThrow(CryptoErrors.unknownKey("nope"));
    MvcTestResult r = get("/internal/v1/keys/nope/public", InternalTokens.forClient(ADMIN));
    assertThat(r).hasStatus(404);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-010");

    when(keys.list()).thenThrow(CryptoErrors.hsmUnavailable("list", null));
    MvcTestResult down = get("/internal/v1/keys", InternalTokens.forClient(ADMIN));
    assertThat(down).hasStatus(503);
    assertThat(down).bodyJson().extractingPath("$.code").isEqualTo("MW-SYS-010");
    assertThat(down).bodyJson().extractingPath("$.retryable").isEqualTo(true);
  }

  @Test
  void needsAValidServiceTokenOfAnAllowedClient() {
    assertThat(mvc.get().uri("/internal/v1/keys").exchange()).hasStatus(401)
        .bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-001");
    assertThat(get("/internal/v1/keys", InternalTokens.expired(ADMIN))).hasStatus(401);
    assertThat(get("/internal/v1/keys", InternalTokens.foreignKey(ADMIN))).hasStatus(401);
    assertThat(get("/internal/v1/keys", InternalTokens.withIssuer(ADMIN, "http://evil/realms/services")))
        .hasStatus(401);
    assertThat(get("/internal/v1/keys", InternalTokens.withAudience(ADMIN, "mw-audit-dw"))).hasStatus(401);
    assertThat(get("/internal/v1/keys", InternalTokens.forClient("mw-portal-api"))).hasStatus(403)
        .bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    verify(keys, never()).list();
  }

  @Test
  void onlyKeyAdminClientsGenerateOrRotate() {
    MvcTestResult gen = mvc.post().uri("/internal/v1/keys")
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-auth"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"JWT_SIGN\"}").exchange();
    assertThat(gen).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    MvcTestResult rot = mvc.post().uri("/internal/v1/keys/jwt-sign/rotate")
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-audit-dw")).exchange();
    assertThat(rot).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    verify(keys, never()).generate(anyString(), any());
    verify(keys, never()).rotate(anyString());

    // reading stays open to every allowed caller
    when(keys.list()).thenReturn(new KeyView.Page(List.of(jwtSign())));
    assertThat(get("/internal/v1/keys", InternalTokens.forClient("mw-auth"))).hasStatusOk();
  }
}
