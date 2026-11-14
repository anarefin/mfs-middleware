package mfs.mw.crypto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import mfs.mw.crypto.config.SecurityConfig;
import mfs.mw.crypto.dto.DerivePinResponse;
import mfs.mw.crypto.dto.Envelope;
import mfs.mw.crypto.dto.HmacView;
import mfs.mw.crypto.dto.JwsSignResponse;
import mfs.mw.crypto.dto.PinHmac;
import mfs.mw.crypto.dto.PlaintextView;
import mfs.mw.crypto.dto.TokensView;
import mfs.mw.crypto.dto.VerifyPinResponse;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.security.InternalTokens;
import mfs.mw.crypto.service.EnvelopeService;
import mfs.mw.crypto.service.HmacService;
import mfs.mw.crypto.service.JwsService;
import mfs.mw.crypto.service.PinService;
import mfs.mw.crypto.service.TokenService;
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
 * The operation endpoints of crypto-internal.v1 (ticket 63): mapping, Bean Validation before the
 * service (a bad block shape or length is MW-VAL-001 and the value never comes back), the error
 * envelope, and the internal service JWT in front.
 */
@WebMvcTest(
    controllers = {PinController.class, JwsController.class, HmacController.class, TokenController.class,
        EnvelopeController.class},
    properties = "spring.test.mockmvc.print=none")
@Import(SecurityConfig.class)
class OperationControllersTest {

  static final String AUTH = "mw-auth";
  static final String CT = "A".repeat(510) + "==";
  static final String HMAC = "A".repeat(43) + "=";

  @DynamicPropertySource
  static void jwks(DynamicPropertyRegistry r) {
    r.add("mw.internal-auth.jwk-set-uri", InternalTokens::jwkSetUri);
  }

  final MockMvcTester mvc;
  @MockitoBean PinService pins;
  @MockitoBean JwsService jws;
  @MockitoBean HmacService hmac;
  @MockitoBean TokenService tokens;
  @MockitoBean EnvelopeService envelopes;

  @Autowired
  OperationControllersTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult post(String path, String json) {
    return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer(AUTH))
        .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
  }

  static String verifyBody(String walletId, String ciphertext, String stored) {
    return "{\"walletId\":\"" + walletId + "\",\"encryptedPin\":{\"keyId\":\"pin-transport\",\"ciphertext\":\""
        + ciphertext + "\"},\"storedHmac\":\"" + stored + "\",\"hmacKeyId\":\"pin-hmac.v1\"}";
  }

  @Test
  void pinVerifyAnswersMatchAndRehash() throws Exception {
    when(pins.verify(any())).thenReturn(new VerifyPinResponse(true, new PinHmac(HMAC, "pin-hmac.v2")));
    MvcTestResult r = post("/internal/v1/pin/verify", verifyBody("01712345678", CT, HMAC));
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.match").isEqualTo(true);
    assertThat(r).bodyJson().extractingPath("$.rehash.hmacKeyId").isEqualTo("pin-hmac.v2");

    when(pins.verify(any())).thenReturn(new VerifyPinResponse(false, null));
    MvcTestResult no = post("/internal/v1/pin/verify", verifyBody("01712345678", CT, HMAC));
    assertThat(no).hasStatusOk();
    assertThat(no.getResponse().getContentAsString()).isEqualTo("{\"match\":false}");
  }

  @Test
  void aMalformedPinRequestIsRefusedBeforeTheServiceAndNeverEchoed() throws Exception {
    MvcTestResult badHmac = post("/internal/v1/pin/verify", verifyBody("01712345678", CT, "c2hvcnQ="));
    assertThat(badHmac).hasStatus(400);
    assertThat(badHmac).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    assertThat(badHmac.getResponse().getContentAsString()).doesNotContain("c2hvcnQ=").doesNotContain("01712345678");

    MvcTestResult badCipher = post("/internal/v1/pin/verify", verifyBody("01712345678", "not base64!", HMAC));
    assertThat(badCipher).hasStatus(400);
    assertThat(badCipher.getResponse().getContentAsString()).doesNotContain("not base64!");

    MvcTestResult nul = post("/internal/v1/pin/verify", verifyBody("0171\\u0000", CT, HMAC));
    assertThat(nul).hasStatus(400);
    verify(pins, never()).verify(any());
  }

  @Test
  void aBadBlockIsMwVal014AndAnHsmOutageMwSys010() {
    when(pins.derive(any())).thenThrow(CryptoErrors.badPinBlock());
    String derive = "{\"walletId\":\"w-1\",\"encryptedPin\":{\"keyId\":\"pin-transport\",\"ciphertext\":\"" + CT + "\"}}";
    MvcTestResult bad = post("/internal/v1/pin/derive", derive);
    assertThat(bad).hasStatus(400);
    assertThat(bad).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-014");

    doThrow(CryptoErrors.hsmUnavailable("pin-decrypt", null)).when(pins).derive(any());
    MvcTestResult down = post("/internal/v1/pin/derive", derive);
    assertThat(down).hasStatus(503);
    assertThat(down).bodyJson().extractingPath("$.code").isEqualTo("MW-SYS-010");

    doReturn(DerivePinResponse.weak("SEQUENCE")).when(pins).derive(any());
    MvcTestResult weak = post("/internal/v1/pin/derive", derive);
    assertThat(weak).hasStatusOk();
    assertThat(weak).bodyJson().extractingPath("$.rule").isEqualTo("SEQUENCE");
  }

  @Test
  void jwsSignAndJwks() {
    when(jws.sign(any())).thenReturn(new JwsSignResponse("a.b.c", "jwt-sign.v1", "RS256"));
    MvcTestResult r = post("/internal/v1/jws/sign", "{\"purpose\":\"JWT_SIGN\",\"payload\":{\"sub\":\"w-1\"}}");
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.jws").isEqualTo("a.b.c");
    assertThat(post("/internal/v1/jws/sign", "{\"purpose\":\"JWT_SIGN\"}")).hasStatus(400);
    assertThat(post("/internal/v1/jws/sign", "{\"purpose\":\"PIN_HMAC\",\"payload\":{}}")).hasStatus(400);

    when(jws.jwks("JWT_SIGN")).thenReturn(Map.of("keys", List.of(Map.of("kid", "jwt-sign.v1"))));
    MvcTestResult set = mvc.get().uri("/internal/v1/jwks/JWT_SIGN")
        .header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer(AUTH)).exchange();
    assertThat(set).hasStatusOk();
    assertThat(set).bodyJson().extractingPath("$.keys[0].kid").isEqualTo("jwt-sign.v1");
  }

  @Test
  void hmacTokensAndEnvelope() {
    when(hmac.sign(any())).thenReturn(HmacView.signed(HMAC, "hmac-webhook.v1"));
    assertThat(post("/internal/v1/hmac/sign", "{\"keyId\":\"hmac-webhook\",\"data\":\"AAAA\"}")).hasStatusOk()
        .bodyJson().extractingPath("$.kid").isEqualTo("hmac-webhook.v1");
    when(hmac.verify(any())).thenReturn(HmacView.verified(false, null));
    assertThat(post("/internal/v1/hmac/verify", "{\"keyId\":\"hmac-webhook\",\"data\":\"AAAA\",\"mac\":\"" + HMAC + "\"}"))
        .hasStatusOk().bodyJson().extractingPath("$.valid").isEqualTo(false);

    when(tokens.tokenise(any())).thenReturn(new TokensView("MSISDN", "tokenise-msisdn.v1", List.of("MSISDN_X")));
    assertThat(post("/internal/v1/tokens", "{\"domain\":\"MSISDN\",\"values\":[\"01712345678\"]}")).hasStatusOk()
        .bodyJson().extractingPath("$.tokens[0]").isEqualTo("MSISDN_X");
    String tooMany = "{\"domain\":\"MSISDN\",\"values\":[" + "\"1\",".repeat(500) + "\"1\"]}";
    assertThat(post("/internal/v1/tokens", tooMany)).hasStatus(400);
    assertThat(post("/internal/v1/tokens", "{\"domain\":\"EMAIL\",\"values\":[\"a\"]}")).hasStatus(400);
    verify(tokens).tokenise(any());

    when(envelopes.encrypt(any())).thenReturn(new Envelope("kek-column.v1", "AAAA", "AAAAAAAAAAAAAAAA", "AAAA", null));
    assertThat(post("/internal/v1/envelope/encrypt", "{\"purpose\":\"KEK_COLUMN\",\"plaintext\":\"AAAA\"}")).hasStatusOk()
        .bodyJson().extractingPath("$.kekId").isEqualTo("kek-column.v1");
    when(envelopes.decrypt(any())).thenReturn(new PlaintextView("AAAA"));
    assertThat(post("/internal/v1/envelope/decrypt",
        "{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"AAAA\",\"iv\":\"AAAAAAAAAAAAAAAA\",\"ciphertext\":\"AAAA\"}"))
        .hasStatusOk().bodyJson().extractingPath("$.plaintext").isEqualTo("AAAA");
    assertThat(post("/internal/v1/envelope/decrypt",
        "{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"AAAA\",\"iv\":\"AAAA\",\"ciphertext\":\"AAAA\"}")).hasStatus(400);
  }

  @Test
  void needsAServiceTokenOfAnAllowedClient() {
    assertThat(mvc.post().uri("/internal/v1/pin/verify").contentType(MediaType.APPLICATION_JSON)
        .content(verifyBody("w-1", CT, HMAC)).exchange()).hasStatus(401);
    assertThat(mvc.post().uri("/internal/v1/tokens").header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-portal-api"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"domain\":\"MSISDN\",\"values\":[\"1\"]}").exchange())
        .hasStatus(403);
    verifyNoInteractions(pins);
  }
}
