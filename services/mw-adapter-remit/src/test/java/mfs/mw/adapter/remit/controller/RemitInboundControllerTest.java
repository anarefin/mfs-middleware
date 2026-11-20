package mfs.mw.adapter.remit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import mfs.mw.adapter.remit.dto.RemitCreditAck;
import mfs.mw.adapter.remit.exception.ErrorCodes;
import mfs.mw.adapter.remit.exception.GlobalExceptionHandler;
import mfs.mw.adapter.remit.exception.MwException;
import mfs.mw.adapter.remit.mapper.InboundCallMapper;
import mfs.mw.adapter.remit.partner.InboundCall;
import mfs.mw.adapter.remit.service.RemitInboundService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** {@code /remit/v1/credits} (ticket 119): partner header, raw body handed on as signed, status codes. */
@WebMvcTest(RemitInboundController.class)
@Import({GlobalExceptionHandler.class, InboundCallMapper.class})
class RemitInboundControllerTest {

  private static final String BODY = "{\"partnerRef\":\"IMT-1\"}";
  private static final String UPAY = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8d";

  private final MockMvcTester mvc;

  @MockitoBean RemitInboundService service;

  @Autowired
  RemitInboundControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void aNewCreditIs202AndTheRawBodyAndPathReachTheService() {
    when(service.credit(any())).thenReturn(new RemitCreditAck("IMT-1", UPAY, "ACCEPTED", false));

    var res = mvc.post().uri("/remit/v1/credits").header("X-Partner-Id", "ucb-imt").header("X-Nonce", "n-1234567890abcdef")
        .contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();

    assertThat(res).hasStatus(202);
    assertThat(res).bodyJson().extractingPath("upayRef").isEqualTo(UPAY);
    ArgumentCaptor<InboundCall> call = ArgumentCaptor.forClass(InboundCall.class);
    verify(service).credit(call.capture());
    assertThat(call.getValue().partnerId()).isEqualTo("ucb-imt");
    assertThat(call.getValue().method()).isEqualTo("POST");
    assertThat(call.getValue().pathAndQuery()).isEqualTo("/remit/v1/credits");
    assertThat(new String(call.getValue().body())).isEqualTo(BODY);
    assertThat(call.getValue().header("X-Nonce")).isEqualTo("n-1234567890abcdef");
  }

  @Test
  void aReSentCreditIs200() {
    when(service.credit(any())).thenReturn(new RemitCreditAck("IMT-1", UPAY, "ACCEPTED", true));

    assertThat(mvc.post().uri("/remit/v1/credits").header("X-Partner-Id", "ucb-imt")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .hasStatus(200)
        .bodyJson().extractingPath("duplicate").isEqualTo(true);
  }

  @Test
  void withoutOrWithABadPartnerIdNothingReachesTheService() {
    assertThat(mvc.post().uri("/remit/v1/credits").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .hasStatus(400);
    assertThat(mvc.post().uri("/remit/v1/credits").header("X-Partner-Id", "UCB IMT")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .hasStatus(400)
        .bodyJson().extractingPath("code").isEqualTo("MW-VAL-001");
    assertThat(mvc.get().uri("/remit/v1/credits/bad ref!").header("X-Partner-Id", "ucb-imt")).hasStatus(400);
    verify(service, never()).credit(any());
    verify(service, never()).status(any(), any());
  }

  @Test
  void aReplayedNonceRefusedByTheServiceIs401WithTheEnvelope() {
    when(service.credit(any()))
        .thenThrow(new MwException(ErrorCodes.AUTH_SIGNATURE, "The request signature is not valid")
            .withStatus(HttpStatus.UNAUTHORIZED));

    assertThat(mvc.post().uri("/remit/v1/credits").header("X-Partner-Id", "ucb-imt")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .hasStatus(401)
        .bodyJson().extractingPath("code").isEqualTo("MW-AUTH-001");
  }

  @Test
  void theStatusQueryNamesThePartnerReference() {
    when(service.status(any(), eq("IMT-1"))).thenReturn(new RemitCreditAck("IMT-1", UPAY, "ACCEPTED", false));

    assertThat(mvc.get().uri("/remit/v1/credits/IMT-1").header("X-Partner-Id", "ucb-imt"))
        .hasStatus(200)
        .bodyJson().extractingPath("status").isEqualTo("ACCEPTED");
  }
}
