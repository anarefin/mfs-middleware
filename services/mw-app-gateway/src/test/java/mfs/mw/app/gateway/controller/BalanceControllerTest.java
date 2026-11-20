package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.BalanceRequest;
import mfs.mw.app.gateway.dto.BalanceView;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.service.BalanceService;
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

/** {@code POST /app/v1/balance} (ADR D26): mapping, validation and the answers of the service. */
@WebMvcTest(BalanceController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class BalanceControllerTest {

  static final String MSISDN = "01711000001";
  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String BODY =
      "{\"encryptedPin\":{\"keyId\":\"PIN_TRANSPORT.v1\",\"ciphertext\":\"Y2lwaGVy\"},\"locale\":\"bn\"}";

  private final MockMvcTester mvc;

  @MockitoBean BalanceService service;

  @Autowired
  BalanceControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor as(String role) {
    return jwt().jwt(j -> j.claim("msisdn", MSISDN).claim("role", role).claim("amr", List.of("pin", "device")));
  }

  MvcTestResult post(RequestPostProcessor auth, String key, String body) {
    var req =
        mvc.post()
            .uri("/app/v1/balance")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .header("X-Correlation-Id", "corr-bal");
    if (auth != null) {
      req = req.with(auth);
    }
    if (key != null) {
      req = req.header("Idempotency-Key", key);
    }
    return req.exchange();
  }

  @Test
  void theBalanceIs200ForEveryAppRole() {
    when(service.check(any(), eq("bal-1"), any()))
        .thenReturn(new BalanceView(TXN_ID, new Amount(1_200_000L, "BDT"), Instant.parse("2026-11-16T08:15:31Z")));

    for (String role : List.of("customer", "agent", "merchant")) {
      MvcTestResult r = post(as(role), "bal-1", BODY);
      assertThat(r).as(role).hasStatus(200);
      assertThat(r).bodyJson().extractingPath("$.txnId").isEqualTo(TXN_ID);
      assertThat(r).bodyJson().extractingPath("$.available.valueMinor").isEqualTo(1_200_000);
      assertThat(r).bodyJson().extractingPath("$.available.currency").isEqualTo("BDT");
      assertThat(r).bodyJson().extractingPath("$.asOf").isEqualTo("2026-11-16T08:15:31Z");
    }
    verify(service).check(
        eq(new AppUser(MSISDN, "MERCHANT_APP", "MERCHANT")), eq("bal-1"),
        eq(new BalanceRequest(new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy"), "bn")));
  }

  @Test
  void theHandlerIsSignedLikeASubmit() throws NoSuchMethodException {
    DeviceGate gate =
        BalanceController.class
            .getMethod("check", org.springframework.security.oauth2.jwt.Jwt.class, String.class, BalanceRequest.class)
            .getAnnotation(DeviceGate.class);
    assertThat(gate).isNotNull();
    assertThat(gate.value()).isEqualTo(DeviceGate.Level.SIGNED);
  }

  @Test
  void aWrongPinIsMwAuthsAnswer() {
    when(service.check(any(), any(), any()))
        .thenThrow(new MwException("MW-AUTH-040", "Wrong PIN").withStatus(HttpStatus.UNAUTHORIZED)
            .withDetails(List.of(new ErrorDetail("triesLeft", "2"))));

    MvcTestResult r = post(as("customer"), "bal-2", BODY);

    assertThat(r).hasStatus(401);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-040");
    assertThat(r).bodyJson().extractingPath("$.details[0].field").isEqualTo("triesLeft");
  }

  @Test
  void aCoreRefusalIs422WithTheTxnId() {
    when(service.check(any(), any(), any()))
        .thenThrow(new MwException("MW-CORE-WALLET_NOT_ACTIVE", "Wallet is not active").withTxnId(TXN_ID));

    MvcTestResult r = post(as("customer"), "bal-3", BODY);

    assertThat(r).hasStatus(422);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-CORE-WALLET_NOT_ACTIVE");
    assertThat(r).bodyJson().extractingPath("$.txnId").isEqualTo(TXN_ID);
  }

  @Test
  void noAnswerInTheBudgetIs504Retryable() {
    when(service.check(any(), any(), any()))
        .thenThrow(new MwException(ErrorCodes.TMO_NO_ANSWER, "No answer in time").withTxnId(TXN_ID));

    MvcTestResult r = post(as("customer"), "bal-4", BODY);

    assertThat(r).hasStatus(504);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-TMO-001");
    assertThat(r).bodyJson().extractingPath("$.retryable").isEqualTo(true);
  }

  @Test
  void noTokenIs401AndNothingIsAsked() {
    MvcTestResult r = post(null, "bal-5", BODY);
    assertThat(r).hasStatus(401);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-001");
    verify(service, never()).check(any(), any(), any());
  }

  @Test
  void theIdempotencyKeyIsRequired() {
    MvcTestResult r = post(as("customer"), null, BODY);
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.details[0].field").isEqualTo("Idempotency-Key");
    verify(service, never()).check(any(), any(), any());
  }

  @Test
  void theBodyIsCheckedAgainstTheContract() {
    for (String bad : List.of(
        "{}",
        "{\"encryptedPin\":{\"keyId\":\"k\",\"ciphertext\":\"c\"},\"amount\":{\"valueMinor\":1,\"currency\":\"BDT\"}}",
        "{\"encryptedPin\":{\"keyId\":\"k\",\"ciphertext\":\"c\"},\"locale\":\"fr\"}",
        "{\"encryptedPin\":{\"keyId\":\"\",\"ciphertext\":\"c\"}}")) {
      MvcTestResult r = post(as("customer"), "bal-6", bad);
      assertThat(r).as(bad).hasStatus(400);
      assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(service, never()).check(any(), any(), any());
  }

  @Test
  void anotherRoleIs403() {
    MvcTestResult r = post(as("ops"), "bal-7", BODY);
    assertThat(r).hasStatus(403);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    verify(service, never()).check(any(), any(), any());
  }
}
