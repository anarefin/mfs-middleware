package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import mfs.mw.core.mock.config.SecurityConfig;
import mfs.mw.core.mock.dto.AccountView;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.BalanceView;
import mfs.mw.core.mock.dto.ChargesView;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.LimitsView;
import mfs.mw.core.mock.dto.OperationResult;
import mfs.mw.core.mock.dto.StatementView;
import mfs.mw.core.mock.dto.TransactionStatusView;
import mfs.mw.core.mock.dto.WalletTransferRequest;
import mfs.mw.core.mock.exception.MwException;
import mfs.mw.core.mock.security.InternalTokens;
import mfs.mw.core.mock.service.CoreWalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The core wallet REST API (FRS 7.2, ADR D31, ticket 93; contracts/api/mw-core-mock/core-wallet.v1.openapi.yaml):
 * mapping, validation, the service token, the assertion header and the envelope of each endpoint. The
 * money rules are {@code CoreWalletRestIT}'s.
 */
@WebMvcTest(controllers = CoreWalletController.class)
@Import(SecurityConfig.class)
class CoreWalletControllerTest {

  private static final String TXN = "0190a7c4-0000-7000-8000-000000000001";
  private static final String ORIGINAL = "0190a7c4-0000-7000-8000-000000000002";
  private static final String CALLER = "mw-orchestrator";
  private static final String HOLD_BODY =
      """
      {"txnId":"%s","txnType":"TOPUP","debitParty":{"type":"WALLET","id":"01711000001"},
       "creditParty":{"type":"MSISDN","id":"01711000001"},"amount":{"valueMinor":5000,"currency":"BDT"},
       "idempotencyKey":"k-1"}
      """.formatted(TXN);

  @DynamicPropertySource
  static void jwks(DynamicPropertyRegistry registry) {
    registry.add("mw.internal-auth.jwk-set-uri", InternalTokens::jwkSetUri);
  }

  private final MockMvcTester mvc;
  @MockitoBean CoreWalletService service;

  @Autowired
  CoreWalletControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  private MockMvcTester.MockMvcRequestBuilder post(String uri, String body) {
    return mvc.post().uri(uri).header("Authorization", InternalTokens.bearer(CALLER))
        .contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private MockMvcTester.MockMvcRequestBuilder get(String uri) {
    return mvc.get().uri(uri).header("Authorization", InternalTokens.bearer(CALLER));
  }

  private static OperationResult result(String status) {
    return new OperationResult(TXN, status, "CORE-20261119-000001", "HOLD-CORE-20261119-000001", null,
        List.of(new CoreEvent.BalanceAfter("01711000001", Amount.bdt(995000))));
  }

  // --- service token (spec 18.2) ---

  @Test
  void withoutAServiceTokenIs401AuthAndNothingRuns() {
    assertThat(mvc.get().uri("/core/v1/balance/01711000001"))
        .hasStatus(401).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-001");
    verifyNoInteractions(service);
  }

  @Test
  void aTokenForAnotherAudienceOrAForeignKeyIs401() {
    for (String token : new String[] {
        InternalTokens.withAudience(CALLER, "mw-orchestrator"), InternalTokens.foreignKey(CALLER), InternalTokens.expired(CALLER)}) {
      assertThat(mvc.get().uri("/core/v1/balance/01711000001").header("Authorization", "Bearer " + token))
          .hasStatus(401).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-001");
    }
    verifyNoInteractions(service);
  }

  @Test
  void aClientThatMayNotCallIs403() {
    assertThat(mvc.get().uri("/core/v1/balance/01711000001").header("Authorization", InternalTokens.bearer("mw-portal-api")))
        .hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
    verifyNoInteractions(service);
  }

  // --- WAL-01, WAL-03 ---

  @Test
  void anAccountIsServed() {
    given(service.account("01711000001"))
        .willReturn(new AccountView("01711000001", "01711000001", "CUSTOMER", "ACTIVE", "FULL", "Rahim Uddin"));

    assertThat(get("/core/v1/accounts/01711000001")).hasStatusOk().bodyJson().isLenientlyEqualTo(
        "{\"walletId\":\"01711000001\",\"type\":\"CUSTOMER\",\"status\":\"ACTIVE\",\"holderName\":\"Rahim Uddin\"}");
  }

  @Test
  void anUnknownAccountIsThe404Envelope() {
    given(service.account("01799999999"))
        .willThrow(new MwException("MW-VAL-003", "Account not found").withStatus(HttpStatus.NOT_FOUND));

    assertThat(get("/core/v1/accounts/01799999999")).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
  }

  @Test
  void aBalanceIsAvailableHeldAndTotal() {
    given(service.balance(eq("01711000001"), any()))
        .willReturn(new BalanceView("01711000001", Amount.bdt(990000), Amount.bdt(10000), Amount.bdt(1000000),
            Instant.parse("2026-11-20T08:00:00Z")));

    assertThat(get("/core/v1/balance/01711000001")).hasStatusOk().bodyJson().isLenientlyEqualTo(
        "{\"available\":{\"valueMinor\":990000,\"currency\":\"BDT\"},\"held\":{\"valueMinor\":10000},"
            + "\"total\":{\"valueMinor\":1000000},\"asOf\":\"2026-11-20T08:00:00Z\"}");
  }

  @Test
  void aCustomersBalanceReadPassesTheCallerTxnAssertionAndKey() {
    given(service.balance(eq("01711000001"), any()))
        .willReturn(new BalanceView("01711000001", Amount.bdt(1), Amount.bdt(0), Amount.bdt(1), Instant.EPOCH));

    assertThat(mvc.get().uri("/core/v1/balance/01711000001?txnId=" + TXN).header("Authorization", InternalTokens.bearer(CALLER))
        .header("X-Txn-Assertion", "a.b.c").header("Idempotency-Key", "k-1")).hasStatusOk();

    verify(service).balance("01711000001", new CoreWalletService.BalanceRead(CALLER, TXN, "a.b.c", "k-1"));
  }

  @Test
  void validateNeedsTheAssertionHeader() {
    String body = HOLD_BODY.replace("TOPUP", "P2P").replace("\"MSISDN\"", "\"WALLET\"").replace("01711000001\"},\"amount", "01711000002\"},\"amount");

    assertThat(post("/core/v1/transactions/validate", body)).hasStatus(401).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-046");
    verifyNoInteractions(service);
  }

  // --- WAL-04, WAL-05 ---

  @Test
  void chargesAreCalculated() {
    given(service.charges(any())).willReturn(
        new ChargesView("P2P", Amount.bdt(10000), Amount.bdt(500), Amount.bdt(65), Amount.bdt(0), java.util.Map.of(), Amount.bdt(10500)));

    assertThat(post("/core/v1/charges/calculate", "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":10000,\"currency\":\"BDT\"}}"))
        .hasStatusOk().bodyJson().extractingPath("$.fee.valueMinor").isEqualTo(500);
  }

  @Test
  void aChargesRequestWithoutAmountOrWithANegativeOneIs400() {
    assertThat(post("/core/v1/charges/calculate", "{\"txnType\":\"P2P\"}"))
        .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    assertThat(post("/core/v1/charges/calculate", "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":-1,\"currency\":\"BDT\"}}"))
        .hasStatus(400);
    assertThat(post("/core/v1/charges/calculate", "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":1,\"currency\":\"USD\"}}"))
        .hasStatus(400);
    assertThat(post("/core/v1/charges/calculate", "not json")).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-002");
    verifyNoInteractions(service);
  }

  @Test
  void limitsAreChecked() {
    given(service.limits(any())).willReturn(new LimitsView("01711000001", false, "LIMIT_EXCEEDED_PER_TXN",
        new LimitsView.Remaining(Amount.bdt(500000), Amount.bdt(1500000), Amount.bdt(20000000), 1000L, 20000L)));

    assertThat(post("/core/v1/limits/check",
        "{\"walletId\":\"01711000001\",\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":600000,\"currency\":\"BDT\"}}"))
        .hasStatusOk().bodyJson().isLenientlyEqualTo("{\"allowed\":false,\"reasonCode\":\"LIMIT_EXCEEDED_PER_TXN\"}");
    assertThat(post("/core/v1/limits/check", "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":1,\"currency\":\"BDT\"}}"))
        .hasStatus(400);
  }

  // --- WAL-06..08: holds ---

  @Test
  void aHoldWithItsAssertionIsHeld() {
    given(service.hold(any(), eq("jws.assertion"))).willReturn(result("HELD"));

    assertThat(post("/core/v1/holds", HOLD_BODY).header("X-Txn-Assertion", "jws.assertion"))
        .hasStatusOk().bodyJson().isLenientlyEqualTo("{\"status\":\"HELD\",\"holdId\":\"HOLD-CORE-20261119-000001\"}");
  }

  @Test
  void aHoldWithoutAssertionIs401Auth046AndNothingRuns() {
    assertThat(post("/core/v1/holds", HOLD_BODY))
        .hasStatus(401).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-046");
    verifyNoInteractions(service);
  }

  @Test
  void aBulkRowNeedsNoAssertion() {
    given(service.transfer(any(), isNull())).willReturn(result("POSTED"));

    assertThat(post("/core/v1/transfers", HOLD_BODY.replace("TOPUP", "BULK_DISBURSE"))).hasStatusOk();
  }

  @Test
  void aHoldWithABadTxnIdOrNoPartyIs400() {
    assertThat(post("/core/v1/holds", HOLD_BODY.replace(TXN, "not-a-uuid")).header("X-Txn-Assertion", "a"))
        .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    assertThat(post("/core/v1/holds", HOLD_BODY.replace("\"debitParty\":{\"type\":\"WALLET\",\"id\":\"01711000001\"},", ""))
        .header("X-Txn-Assertion", "a")).hasStatus(400);
    verifyNoInteractions(service);
  }

  @Test
  void aCommitAndAReleaseNeedNoBody() {
    given(service.commit(eq("HOLD-1"), any())).willReturn(result("COMMITTED"));
    given(service.release(eq("HOLD-2"), any())).willReturn(result("RELEASED"));

    assertThat(mvc.post().uri("/core/v1/holds/HOLD-1/commit").header("Authorization", InternalTokens.bearer(CALLER)))
        .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMMITTED");
    assertThat(post("/core/v1/holds/HOLD-2/release", "{\"reason\":\"partner failed\"}"))
        .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("RELEASED");
  }

  @Test
  void aCoreRefusalIsThe422CoreEnvelopeWithTheTxnId() {
    given(service.commit(eq("HOLD-9"), any()))
        .willThrow(new MwException("MW-CORE-HOLD_RELEASED", "Hold was already released").withTxnId(TXN));

    assertThat(post("/core/v1/holds/HOLD-9/commit", "{}")).hasStatus(422).bodyJson()
        .isLenientlyEqualTo("{\"code\":\"MW-CORE-HOLD_RELEASED\",\"txnId\":\"" + TXN + "\",\"retryable\":false}");
  }

  // --- WAL-09..11 ---

  @Test
  void aTransferWithoutAssertionIs401Auth046() {
    assertThat(post("/core/v1/transfers", HOLD_BODY.replace("TOPUP", "P2P")))
        .hasStatus(401).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-046");
    verifyNoInteractions(service);
  }

  @Test
  void aTransferHandsTheBodyAndAssertionOn() {
    given(service.transfer(any(), eq("jws"))).willReturn(result("POSTED"));

    assertThat(post("/core/v1/transfers", HOLD_BODY.replace("TOPUP", "P2P")).header("X-Txn-Assertion", "jws")).hasStatusOk();
    verify(service).transfer(eq(new WalletTransferRequest(TXN, "P2P",
        new mfs.mw.core.mock.dto.Party("WALLET", "01711000001", null, null),
        new mfs.mw.core.mock.dto.Party("MSISDN", "01711000001", null, null), Amount.bdt(5000), "k-1", null)), eq("jws"));
  }

  @Test
  void aCreditNeedsNoAssertion() {
    given(service.credit(any())).willReturn(result("CREDITED"));

    assertThat(post("/core/v1/credits",
        "{\"txnId\":\"" + TXN + "\",\"txnType\":\"ADD_MONEY_BANK\",\"creditParty\":{\"type\":\"WALLET\",\"id\":\"01711000001\"},"
            + "\"amount\":{\"valueMinor\":5000,\"currency\":\"BDT\"}}"))
        .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CREDITED");
  }

  @Test
  void aReversalMayLeaveTheAmountOutButNeedsTheOriginal() {
    given(service.reverse(any())).willReturn(result("REVERSED"));

    assertThat(post("/core/v1/reversals", "{\"txnId\":\"" + TXN + "\",\"originalTxnId\":\"" + ORIGINAL + "\"}")).hasStatusOk();
    assertThat(post("/core/v1/reversals", "{\"txnId\":\"" + TXN + "\"}"))
        .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
  }

  @Test
  void anIdempotencyConflictIs409() {
    given(service.reverse(any())).willThrow(new MwException("MW-IDEM-001", "txnId reused with a different body"));

    assertThat(post("/core/v1/reversals", "{\"txnId\":\"" + TXN + "\",\"originalTxnId\":\"" + ORIGINAL + "\"}"))
        .hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("MW-IDEM-001");
  }

  // --- WAL-12, WAL-13 ---

  @Test
  void aStatementNeedsTheWalletAndTheRange() {
    Instant from = Instant.parse("2026-11-01T00:00:00Z");
    Instant to = Instant.parse("2026-11-19T00:00:00Z");
    given(service.statement(eq("01711000001"), eq(from), eq(to), anyInt(), anyInt()))
        .willReturn(new StatementView("01711000001", from, to, 0, false, List.of()));

    assertThat(get("/core/v1/statements?walletId=01711000001&from=2026-11-01T00:00:00Z&to=2026-11-19T00:00:00Z"))
        .hasStatusOk().bodyJson().extractingPath("$.entries").asArray().isEmpty();
    assertThat(get("/core/v1/statements?walletId=01711000001&from=2026-11-01T00:00:00Z")).hasStatus(400);
    assertThat(get("/core/v1/statements?walletId=01711000001&from=x&to=2026-11-19T00:00:00Z")).hasStatus(400);
    assertThat(get("/core/v1/statements?walletId=01711000001&from=2026-11-01T00:00:00Z&to=2026-11-19T00:00:00Z&limit=501"))
        .hasStatus(400);
  }

  @Test
  void aTransactionStatusIsServedAndABadTxnIdIs400() {
    given(service.transaction(TXN)).willReturn(new TransactionStatusView(TXN, "NOT_FOUND", null, List.of()));

    assertThat(get("/core/v1/transactions/" + TXN)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("NOT_FOUND");
    assertThat(get("/core/v1/transactions/not-a-uuid")).hasStatus(400);
  }
}
