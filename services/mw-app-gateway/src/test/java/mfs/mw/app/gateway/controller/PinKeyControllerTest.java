package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.PinKey;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.PinKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code GET /app/v1/pin-key} (ticket 72): the transport key behind the token. */
@WebMvcTest(PinKeyController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class PinKeyControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean PinKeyService keys;

  @Autowired
  PinKeyControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void servesTheTransportKeyToAnAuthenticatedApp() {
    when(keys.current()).thenReturn(new PinKey("PIN_TRANSPORT.v1", "RSA-OAEP-SHA-256", "-----BEGIN PUBLIC KEY-----"));

    MvcTestResult r = mvc.get().uri("/app/v1/pin-key")
        .with(jwt().jwt(j -> j.claim("msisdn", "01711000001").claim("role", "customer").claim("amr", List.of("pin"))))
        .exchange();

    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.keyId").isEqualTo("PIN_TRANSPORT.v1");
    assertThat(r).bodyJson().extractingPath("$.algorithm").isEqualTo("RSA-OAEP-SHA-256");
  }

  @Test
  void withoutATokenIs401AndAnOutageIs503() {
    assertThat(mvc.get().uri("/app/v1/pin-key").exchange()).hasStatus(401);

    when(keys.current()).thenThrow(
        new MwException("MW-SYS-012", "Service temporarily unavailable, try again").withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    MvcTestResult r = mvc.get().uri("/app/v1/pin-key")
        .with(jwt().jwt(j -> j.claim("msisdn", "01711000001").claim("role", "customer").claim("amr", List.of("pin"))))
        .exchange();
    assertThat(r).hasStatus(503);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-SYS-012");
  }
}
