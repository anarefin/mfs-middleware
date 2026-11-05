package mfs.mw.checkout.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import mfs.mw.checkout.dto.CreateSessionRequest;
import mfs.mw.checkout.dto.SessionCreated;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.service.MerchantSessionService;
import mfs.mw.checkout.service.SessionBodySchemaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code POST /checkout/v1/sessions}: the body schema is checked here on the exact bytes received
 * (APISIX no longer validates bodies, M3 gate decision), before the body is bound and before the
 * service runs; failures are 400 MW-VAL-001.
 */
@WebMvcTest(controllers = MerchantSessionController.class, properties = "spring.test.mockmvc.print=none")
class MerchantSessionControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean MerchantSessionService sessions;

  @Autowired
  MerchantSessionControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult post(String body) {
    return mvc.post()
        .uri("/checkout/v1/sessions")
        .header(MerchantSessionController.MERCHANT_HEADER, "merchant_demo")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  @Test
  void aValidBodyReachesTheService() {
    when(sessions.create(eq("merchant_demo"), any(CreateSessionRequest.class)))
        .thenReturn(new MerchantSessionService.Created(
            new SessionCreated("s-1", "https://pay.example.test/t", SessionStatus.OPEN, Instant.parse("2026-11-05T10:15:00Z")),
            true));

    MvcTestResult r = post(SessionBodySchemaTest.VALID);

    assertThat(r).hasStatus(201);
    verify(sessions).create(eq("merchant_demo"), any(CreateSessionRequest.class));
  }

  @Test
  void aBodyBreakingTheSchemaIsRefusedBeforeTheService() {
    MvcTestResult r = post(SessionBodySchemaTest.VALID.replace("\"BDT\"", "\"USD\""));

    assertThat(r).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(sessions, never()).create(any(), any());
  }

  /** Jackson alone would bind the last of two orderIds; the schema check refuses the body. */
  @Test
  void aDuplicateKeyIsRefused() {
    MvcTestResult r =
        post(SessionBodySchemaTest.VALID.replace("\"orderId\":\"ORD-1\",", "\"orderId\":\"ORD-1\",\"orderId\":\"ORD-2\","));

    assertThat(r).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(sessions, never()).create(any(), any());
  }

  @Test
  void anOversizedBodyIsRefusedWith413() {
    MvcTestResult r = post(SessionBodySchemaTest.VALID.replace("\"Order 1\"", "\"" + "x".repeat(70_000) + "\""));

    assertThat(r).hasStatus(413).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-BODY_TOO_LARGE");
    verify(sessions, never()).create(any(), any());
  }
}
