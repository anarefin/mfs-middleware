package mfs.mw.adapter.bank.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import mfs.mw.adapter.bank.config.SecurityConfig;
import mfs.mw.adapter.bank.dto.AccountValidationView;
import mfs.mw.adapter.bank.exception.GlobalExceptionHandler;
import mfs.mw.adapter.bank.service.AccountValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** {@code POST /bank/v1/accounts/validate} (ticket 117b): an allowed client's token, then the body. */
@WebMvcTest(AccountValidationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(
    properties = {
      "mw.internal-auth.jwk-set-uri=http://keycloak.test/certs",
      "mw.internal-auth.issuer=http://keycloak.test/realms/services",
      "mw.internal-auth.audience=mw-adapter-bank",
      "mw.internal-auth.allowed-clients=mw-app-gateway"
    })
class AccountValidationControllerTest {

  private static final String BODY = "{\"bankCode\":\"dbbl\",\"accountNumber\":\"1234567890\"}";

  private final MockMvcTester mvc;

  @MockitoBean AccountValidationService service;
  @MockitoBean JwtDecoder decoder;

  @Autowired
  AccountValidationControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void theAppGatewayGetsTheMaskedAnswer() throws Exception {
    when(service.validate("dbbl", "1234567890", null)).thenReturn(new AccountValidationView("dbbl", "7890", "R."));

    var res = mvc.post().uri("/bank/v1/accounts/validate")
        .with(jwt().jwt(j -> j.claim("azp", "mw-app-gateway")))
        .contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();

    assertThat(res).hasStatus(200);
    assertThat(res).bodyJson().extractingPath("accountLast4").isEqualTo("7890");
    assertThat(res).bodyJson().extractingPath("holderName").isEqualTo("R.");
    assertThat(res.getResponse().getContentAsString()).doesNotContain("1234567890");
  }

  @Test
  void noTokenIs401() {
    assertThat(mvc.post().uri("/bank/v1/accounts/validate").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .hasStatus(401)
        .bodyJson().extractingPath("code").isEqualTo("MW-AUTH-001");
    verify(service, never()).validate(any(), any(), any());
  }

  @Test
  void anotherClientIs403() {
    assertThat(mvc.post().uri("/bank/v1/accounts/validate")
            .with(jwt().jwt(j -> j.claim("azp", "mw-ussd")))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .hasStatus(403)
        .bodyJson().extractingPath("code").isEqualTo("MW-AUTH-002");
    verify(service, never()).validate(any(), any(), any());
  }

  @Test
  void aBadAccountNumberIsRejectedBeforeTheService() {
    assertThat(mvc.post().uri("/bank/v1/accounts/validate")
            .with(jwt().jwt(j -> j.claim("azp", "mw-app-gateway")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bankCode\":\"dbbl\",\"accountNumber\":\"12ab\"}"))
        .hasStatus(400);
    verify(service, never()).validate(any(), any(), any());
  }
}
