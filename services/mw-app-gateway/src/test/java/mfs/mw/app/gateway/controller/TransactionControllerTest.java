package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.TransactionService;
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

/** The app API surface: JWT, Idempotency-Key and body validation before anything is published. */
@WebMvcTest(TransactionController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class TransactionControllerTest {

  static final String MSISDN = "01711000001";
  static final AppUser CUSTOMER = new AppUser(MSISDN, "CUSTOMER_APP", "CUSTOMER");
  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String BODY =
      """
      {"txnType":"P2P","creditParty":{"id":"01711000002"},
       "amount":{"valueMinor":%d,"currency":"BDT"},
       "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVy"}}
      """;

  private final MockMvcTester mvc;

  @MockitoBean TransactionService service;

  @Autowired
  TransactionControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor customer() {
    return jwt()
        .jwt(j -> j.claim("msisdn", MSISDN).claim("realm_access", Map.of("roles", List.of("customer"))));
  }

  MvcTestResult post(RequestPostProcessor auth, String key, String body) {
    var req =
        mvc.post()
            .uri("/app/v1/transactions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .header("X-Correlation-Id", "corr-ctl");
    if (auth != null) {
      req = req.with(auth);
    }
    if (key != null) {
      req = req.header("Idempotency-Key", key);
    }
    return req.exchange();
  }

  @Test
  void noTokenIs401WithTheEnvelope() {
    MvcTestResult r = post(null, "key-1", BODY.formatted(1000));
    assertThat(r).hasStatus(401);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-001");
    assertThat(r).bodyJson().extractingPath("$.correlationId").isEqualTo("corr-ctl");
    verify(service, never()).submit(any(), any(), any());
  }

  @Test
  void missingIdempotencyKeyIsMwVal() {
    MvcTestResult r = post(customer(), null, BODY.formatted(1000));
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    assertThat(r).bodyJson().extractingPath("$.details[0].field").isEqualTo("Idempotency-Key");
    verify(service, never()).submit(any(), any(), any());
  }

  @Test
  void invalidIdempotencyKeyIsMwVal() {
    for (String bad : List.of("x".repeat(65), "has space", "")) {
      MvcTestResult r = post(customer(), bad, BODY.formatted(1000));
      assertThat(r).as(bad).hasStatus(400);
      assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
      assertThat(r).bodyJson().extractingPath("$.details[0].field").isEqualTo("Idempotency-Key");
    }
    verify(service, never()).submit(any(), any(), any());
  }

  @Test
  void amountZeroOrNegativeIsRejected() {
    for (long amount : new long[] {0, -500}) {
      MvcTestResult r = post(customer(), "key-amt", BODY.formatted(amount));
      assertThat(r).hasStatus(400);
      assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
      assertThat(r)
          .bodyJson()
          .extractingPath("$.details[*].field")
          .asArray()
          .containsExactly("amount.valueMinor");
    }
    verify(service, never()).submit(any(), any(), any());
  }

  @Test
  void missingPinOrBadMsisdnIsMwVal() {
    MvcTestResult r =
        post(
            customer(),
            "key-2",
            "{\"txnType\":\"P2P\",\"creditParty\":{\"id\":\"12345\"},"
                + "\"amount\":{\"valueMinor\":100,\"currency\":\"BDT\"}}");
    assertThat(r).hasStatus(400);
    assertThat(r)
        .bodyJson()
        .extractingPath("$.details[*].field")
        .asArray()
        .containsExactlyInAnyOrder("creditParty.id", "encryptedPin");
  }

  @Test
  void tokenWithoutMsisdnOrCustomerRoleIsForbidden() {
    MvcTestResult noMsisdn =
        post(
            jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("customer")))),
            "k",
            BODY.formatted(100));
    assertThat(noMsisdn).hasStatus(403);
    assertThat(noMsisdn).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");

    MvcTestResult agent =
        post(
            jwt()
                .jwt(
                    j ->
                        j.claim("msisdn", "01811000001")
                            .claim("realm_access", Map.of("roles", List.of("agent")))),
            "k",
            BODY.formatted(100));
    assertThat(agent).hasStatus(403);
    assertThat(agent).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    verify(service, never()).submit(any(), any(), any());
  }

  @Test
  void terminalResultIs200AndBudgetExpiryIs202() {
    TransactionView done = TransactionView.processing(TXN_ID).withStatus("COMPLETED");
    when(service.submit(eq(CUSTOMER), eq("key-ok"), any())).thenReturn(SubmitResult.done(done));
    MvcTestResult ok = post(customer(), "key-ok", BODY.formatted(1000));
    assertThat(ok).hasStatus(200);
    assertThat(ok).bodyJson().extractingPath("$.txnId").isEqualTo(TXN_ID);
    assertThat(ok).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

    when(service.submit(any(), eq("key-slow"), any()))
        .thenReturn(SubmitResult.processing(TransactionView.processing(TXN_ID)));
    MvcTestResult slow = post(customer(), "key-slow", BODY.formatted(1000));
    assertThat(slow).hasStatus(202);
    assertThat(slow).bodyJson().extractingPath("$.status").isEqualTo("PROCESSING");
    assertThat(slow).bodyJson().extractingPath("$.txnId").isEqualTo(TXN_ID);
    assertThat(slow.getResponse().getHeader("Location"))
        .isEqualTo("/app/v1/transactions/" + TXN_ID);
  }

  @Test
  void serviceFailureIsTheEnvelope() {
    when(service.submit(any(), any(), any()))
        .thenThrow(
            new MwException("MW-SYS-010", "Service temporarily unavailable")
                .withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    MvcTestResult r = post(customer(), "key-down", BODY.formatted(1000));
    assertThat(r).hasStatus(503);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-SYS-010");
    assertThat(r).bodyJson().extractingPath("$.retryable").isEqualTo(true);
  }

  @Test
  void getReturnsTheViewForTheOwner() {
    when(service.find(CUSTOMER, TXN_ID))
        .thenReturn(TransactionView.processing(TXN_ID).withStatus("POSTING"));
    MvcTestResult r = mvc.get().uri("/app/v1/transactions/" + TXN_ID).with(customer()).exchange();
    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.status").isEqualTo("POSTING");
  }

  @Test
  void getWithoutTokenIs401() {
    assertThat(mvc.get().uri("/app/v1/transactions/" + TXN_ID).exchange()).hasStatus(401);
  }
}
