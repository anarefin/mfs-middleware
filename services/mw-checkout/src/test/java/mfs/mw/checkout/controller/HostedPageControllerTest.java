package mfs.mw.checkout.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import mfs.mw.checkout.dto.ConfirmRequest;
import mfs.mw.checkout.dto.OtpSent;
import mfs.mw.checkout.dto.PayResult;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.service.CheckoutPayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Hosted page API bodies ({@code /otp}, {@code /confirm}): the contract-derived schema is checked
 * here on the exact bytes received (APISIX validates no body, M3 gate decision A6), before the
 * body is bound and before the service runs; failures are 400 MW-VAL-001. {@code /cancel} has no
 * body.
 */
@WebMvcTest(controllers = HostedPageController.class, properties = "spring.test.mockmvc.print=none")
class HostedPageControllerTest {

  static final String OTP = "{\"wallet\":\"01711000001\"}";
  static final String CONFIRM =
      "{\"otp\":\"123456\",\"encryptedPin\":{\"keyId\":\"core-2026\",\"ciphertext\":\"QUJD\"}}";

  private final MockMvcTester mvc;

  @MockitoBean CheckoutPayService pay;

  @Autowired
  HostedPageControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult post(String path, String body) {
    return mvc.post()
        .uri("/checkout/pay/v1/tok-1" + path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  @Test
  void aValidOtpBodyReachesTheService() {
    when(pay.sendOtp("tok-1", "01711000001"))
        .thenReturn(new OtpSent("0171****001", Instant.parse("2026-11-05T10:15:00Z"), 2));

    assertThat(post("/otp", OTP)).hasStatus(202);
    verify(pay).sendOtp("tok-1", "01711000001");
  }

  @Test
  void anOtpBodyBreakingTheSchemaIsRefusedBeforeTheService() {
    for (String body : new String[] {"{}", "{\"wallet\":1711000001}", "[]", OTP + " {}", "{\"wallet\":"}) {
      assertThat(post("/otp", body)).as(body).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(pay, never()).sendOtp(any(), any());
  }

  /** Jackson alone would bind the last of two wallets; the schema check refuses the body. */
  @Test
  void aDuplicateWalletIsRefused() {
    MvcTestResult r = post("/otp", "{\"wallet\":\"01711000001\",\"wallet\":\"01711000002\"}");

    assertThat(r).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(pay, never()).sendOtp(any(), any());
  }

  @Test
  void aValidConfirmBodyReachesTheService() {
    when(pay.confirm(eq("tok-1"), any(ConfirmRequest.class))).thenReturn(new PayResult(SessionStatus.PAID, null, null, null));

    assertThat(post("/confirm", CONFIRM)).hasStatus(200);
    verify(pay).confirm(eq("tok-1"), any(ConfirmRequest.class));
  }

  @Test
  void aConfirmBodyBreakingTheSchemaIsRefusedBeforeTheService() {
    for (String body :
        new String[] {
          "{\"otp\":\"123456\"}",
          CONFIRM.replace("\"QUJD\"", "\"" + "A".repeat(4097) + "\""),
          CONFIRM.replace("\"otp\":\"123456\"", "\"otp\":123456"),
          CONFIRM.replace("\"otp\":\"123456\",", "\"otp\":\"123456\",\"otp\":\"654321\","),
        }) {
      assertThat(post("/confirm", body)).as(body).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(pay, never()).confirm(any(), any());
  }

  @Test
  void anOversizedBodyIsRefusedWith413() {
    MvcTestResult r = post("/confirm", CONFIRM.replace("\"QUJD\"", "\"" + "A".repeat(70_000) + "\""));

    assertThat(r).hasStatus(413).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-BODY_TOO_LARGE");
    verify(pay, never()).confirm(any(), any());
  }
}
