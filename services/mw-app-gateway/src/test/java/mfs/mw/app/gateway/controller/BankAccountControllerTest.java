package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.LinkedBankAccount;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.LinkedAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Linked bank accounts (ticket 117b): the token's wallet only, never the full number. */
@WebMvcTest(BankAccountController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class BankAccountControllerTest {

  static final Instant AT = Instant.parse("2026-11-20T08:00:00Z");
  static final String MINE = "01711000001";
  static final String ID = "11111111-1111-1111-1111-111111111111";
  static final String BODY = "{\"bankCode\":\"dbbl\",\"accountNumber\":\"1234567890\"}";

  private final MockMvcTester mvc;

  @MockitoBean LinkedAccountService service;

  @Autowired
  BankAccountControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor token(String msisdn) {
    return jwt().jwt(j -> j.claim("msisdn", msisdn).claim("role", "customer").claim("amr", List.of("pin")));
  }

  @Test
  void linkingAnswersTheLastFourOnly() throws Exception {
    when(service.link(MINE, "dbbl", "1234567890")).thenReturn(new LinkedBankAccount(ID, "dbbl", "7890", "R.", AT));

    MvcTestResult res = mvc.post().uri("/app/v1/bank-accounts").with(token(MINE))
        .contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();

    assertThat(res).hasStatus(201);
    assertThat(res).bodyJson().extractingPath("$.accountLast4").isEqualTo("7890");
    assertThat(res).bodyJson().extractingPath("$.holderName").isEqualTo("R.");
    assertThat(res.getResponse().getContentAsString()).doesNotContain("1234567890");
  }

  @Test
  void theListIsTheTokensWallet() {
    when(service.list(MINE)).thenReturn(new LinkedBankAccount.Page(List.of(new LinkedBankAccount(ID, "dbbl", "7890", "R.", AT))));

    assertThat(mvc.get().uri("/app/v1/bank-accounts").with(token(MINE)))
        .hasStatusOk().bodyJson().extractingPath("$.accounts[0].accountLast4").isEqualTo("7890");
  }

  @Test
  void aBankRefusalPassesThrough() {
    when(service.link(MINE, "dbbl", "1234567890"))
        .thenThrow(new MwException("MW-PTR-BANK_DBBL-ACCOUNT_NOT_FOUND", "no such account").withStatus(HttpStatus.BAD_GATEWAY));

    assertThat(mvc.post().uri("/app/v1/bank-accounts").with(token(MINE))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .hasStatus(502).bodyJson().extractingPath("$.code").isEqualTo("MW-PTR-BANK_DBBL-ACCOUNT_NOT_FOUND");
  }

  @Test
  void aBadNumberNeverReachesTheService() {
    assertThat(mvc.post().uri("/app/v1/bank-accounts").with(token(MINE))
            .contentType(MediaType.APPLICATION_JSON).content("{\"bankCode\":\"dbbl\",\"accountNumber\":\"12-34\"}"))
        .hasStatus(400);
    verify(service, never()).link(any(), any(), any());
  }

  @Test
  void anotherWalletsIdIs404() {
    doThrow(new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND))
        .when(service).unlink(MINE, ID);

    assertThat(mvc.delete().uri("/app/v1/bank-accounts/" + ID).with(token(MINE))).hasStatus(404);
  }

  @Test
  void noTokenIs401() {
    assertThat(mvc.get().uri("/app/v1/bank-accounts")).hasStatus(401);
  }
}
