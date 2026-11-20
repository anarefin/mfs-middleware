package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.AtmTokenRequest;
import mfs.mw.app.gateway.dto.AtmTokenView;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.service.AtmTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code POST /app/v1/atm/tokens} (ticket 120b2): signed, the idempotency key, validation, 201. */
@WebMvcTest(AtmTokenController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class AtmTokenControllerTest {

  static final String MSISDN = "01711000001";
  static final String BODY = "{\"amount\":{\"valueMinor\":200000,\"currency\":\"BDT\"},"
      + "\"encryptedPin\":{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\"Y2lwaGVy\"}}";

  private final MockMvcTester mvc;

  @MockitoBean AtmTokenService service;

  @Autowired
  AtmTokenControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult post(String key, String body) {
    var req = mvc.post().uri("/app/v1/atm/tokens").contentType(MediaType.APPLICATION_JSON).content(body)
        .header("X-Device-Id", "dev-0001")
        .with(jwt().jwt(j -> j.claim("msisdn", MSISDN).claim("role", "customer").claim("amr", List.of("pin"))));
    return key == null ? req.exchange() : req.header("Idempotency-Key", key).exchange();
  }

  @Test
  void theTokenIsAnswered201WithItsExpiryAndAmount() {
    when(service.issue(any(), eq("key-1"), any())).thenReturn(
        new AtmTokenView("123456", Instant.parse("2026-11-20T12:10:00Z"), new Amount(200000L, "BDT")));

    MvcTestResult r = post("key-1", BODY);

    assertThat(r).hasStatus(201);
    assertThat(r).bodyJson().extractingPath("$.token").isEqualTo("123456");
    assertThat(r).bodyJson().extractingPath("$.expiresAt").isEqualTo("2026-11-20T12:10:00Z");
    assertThat(r).bodyJson().extractingPath("$.amount.valueMinor").isEqualTo(200000);
    ArgumentCaptor<AppUser> user = ArgumentCaptor.forClass(AppUser.class);
    ArgumentCaptor<AtmTokenRequest> body = ArgumentCaptor.forClass(AtmTokenRequest.class);
    verify(service).issue(user.capture(), eq("key-1"), body.capture());
    assertThat(user.getValue().msisdn()).isEqualTo(MSISDN);
    assertThat(body.getValue().amount()).isEqualTo(new Amount(200000L, "BDT"));
  }

  @Test
  void noKeyNoPinOrNoPositiveAmountIs400() {
    assertThat(post(null, BODY)).hasStatus(400);
    assertThat(post("key-1", "{\"amount\":{\"valueMinor\":200000,\"currency\":\"BDT\"}}")).hasStatus(400);
    assertThat(post("key-1", BODY.replace("200000", "0"))).hasStatus(400);
    assertThat(post("key-1", BODY.replace("BDT", "USD"))).hasStatus(400);
    verifyNoInteractions(service);
  }

  @Test
  void mwAuthsRefusalPassesThrough() {
    when(service.issue(any(), any(), any()))
        .thenThrow(new MwException("MW-AUTH-040", "Wrong PIN").withStatus(HttpStatus.UNAUTHORIZED));

    MvcTestResult r = post("key-1", BODY);

    assertThat(r).hasStatus(401);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-040");
  }

  @Test
  void theIssueIsASignedRequest() throws Exception {
    DeviceGate gate = AtmTokenController.class.getMethod("issue",
        org.springframework.security.oauth2.jwt.Jwt.class, String.class, AtmTokenRequest.class)
        .getAnnotation(DeviceGate.class);
    assertThat(gate.value()).isEqualTo(DeviceGate.Level.SIGNED);
  }
}
