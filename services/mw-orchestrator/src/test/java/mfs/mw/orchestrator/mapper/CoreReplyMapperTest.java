package mfs.mw.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.Balance;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.dto.CoreReply.Kind;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * A REST answer of the core (ADR D31, ticket 95a) to the saga event of its step: 2xx the step's
 * success, a 422 {@code MW-CORE-*} or 401 {@code MW-AUTH-046} its refusal with that error, an
 * ambiguous outcome (5xx, timeout) no event at all: the step's deadline settles it (POSTING to
 * UNKNOWN, HOLDING to a WAL-13 query, COMMITTING resends).
 */
class CoreReplyMapperTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private final CoreReplyMapper mapper = new CoreReplyMapper();

  private CoreReply answered(String commandType, String body) {
    return new CoreReply(commandType, "txn:step", "txn", Kind.ANSWERED, 200, null, json.readTree(body));
  }

  private CoreReply refused(String commandType, int status, String code) {
    return new CoreReply(commandType, "txn:step", "txn", Kind.REFUSED, status, code,
        json.readTree("{\"code\":\"" + code + "\",\"message\":\"m\",\"retryable\":false,\"details\":[]}"));
  }

  @ParameterizedTest
  @CsvSource({
    "PostTransfer,PostSucceeded,TransferPosted",
    "HoldFunds,HoldPlaced,FundsHeld",
    "CommitHold,CommitAcknowledged,HoldCommitted",
    "ReleaseHold,ReleaseAcknowledged,HoldReleased",
    "ReverseTransaction,ReversalAcknowledged,TransactionReversed",
    "CreditWallet,CommitAcknowledged,WalletCredited",
    "BalanceInquiry,BalanceReported,BalanceReported"
  })
  void a2xxIsTheStepsSuccess(String commandType, String expected, String result) {
    CoreReply reply = answered(commandType, "{\"coreRef\":\"CORE-1\"}");

    assertThat(mapper.toSagaEvent(reply, false, false)).get().extracting(e -> e.getClass().getSimpleName()).isEqualTo(expected);
    assertThat(mapper.result(reply)).isEqualTo(result);
    assertThat(mapper.outcome(reply)).isEqualTo("SUCCESS");
    assertThat(mapper.coreRef(reply)).isEqualTo("CORE-1");
  }

  @ParameterizedTest
  @CsvSource({
    "ValidateTransaction,ValidationRejected,TransactionRejected",
    "PostTransfer,PostRejected,TransferRejected",
    "HoldFunds,HoldRejected,HoldRejected",
    "CommitHold,CommitRefused,CommitFailed",
    "ReleaseHold,ReleaseRefused,ReleaseFailed",
    "ReverseTransaction,ReversalFailed,ReversalRejected",
    "CreditWallet,CommitRefused,CreditRejected",
    "BalanceInquiry,BalanceInquiryRejected,BalanceInquiryRejected"
  })
  void a422CoreRejectIsTheStepsRefusalWithTheCoreReason(String commandType, String expected, String result) {
    CoreReply reply = refused(commandType, 422, "MW-CORE-INSUFFICIENT_BALANCE");

    assertThat(mapper.toSagaEvent(reply, false, false)).get().extracting(e -> e.getClass().getSimpleName()).isEqualTo(expected);
    assertThat(mapper.result(reply)).isEqualTo(result);
    assertThat(mapper.outcome(reply)).isEqualTo("FAILURE");
    assertThat(mapper.reasonCode(reply)).isEqualTo("INSUFFICIENT_BALANCE");
    ErrorInfo error = mapper.error(reply);
    assertThat(error.code()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(error.message()).isEqualTo("Insufficient balance");
    assertThat(error.messageBn()).isNotBlank();
    assertThat(error.retryable()).isFalse();
  }

  @Test
  void aRefusedAssertionIsARefusalWithMwAuth046() {
    CoreReply reply = refused("PostTransfer", 401, "MW-AUTH-046");

    assertThat(mapper.toSagaEvent(reply, false, false)).contains(new PostRejected());
    assertThat(mapper.error(reply).code()).isEqualTo("MW-AUTH-046");
    assertThat(mapper.error(reply).message()).isEqualTo("Transaction authorisation invalid or already used; enter your PIN again");
  }

  @Test
  void anotherCodeStaysWithinTheCorePattern() {
    assertThat(mapper.error(refused("PostTransfer", 400, "MW-VAL-001")).code()).isEqualTo("MW-CORE-UNKNOWN");
    assertThat(mapper.error(refused("PostTransfer", 422, "MW-CORE-NEW_RULE_7")))
        .satisfies(e -> {
          assertThat(e.code()).isEqualTo("MW-CORE-NEW_RULE_7");
          assertThat(e.message()).isEqualTo("Rejected by the core");
        });
  }

  @ParameterizedTest
  @CsvSource({"PostTransfer,503", "PostTransfer,500", "HoldFunds,0", "ValidateTransaction,0", "CommitHold,504"})
  void a5xxOrATimeoutIsNoEventSoTheDeadlineDecides(String commandType, int status) {
    CoreReply reply = new CoreReply(commandType, "txn:step", "txn", Kind.AMBIGUOUS, status, null, null);

    assertThat(mapper.toSagaEvent(reply, true, false)).isEmpty();
    assertThat(mapper.result(reply)).isNull();
  }

  @Test
  void validatedCarriesTheFlowsDecisionsAndTheFee() {
    CoreReply reply = answered("ValidateTransaction", "{\"status\":\"VALIDATED\",\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"}}");

    assertThat(mapper.toSagaEvent(reply, true, false)).contains(new Validated(true));
    assertThat(mapper.toSagaEvent(reply, false, true)).contains(new Validated(false, true));
    assertThat(mapper.feeMinor(reply)).isEqualTo(500L);
    assertThat(mapper.feeMinor(answered("PostTransfer", "{}"))).isNull();
  }

  @Test
  void aHoldsIdIsReadFromItsAnswer() {
    assertThat(mapper.holdId(answered("HoldFunds", "{\"status\":\"HELD\",\"holdId\":\"HOLD-1\"}"))).isEqualTo("HOLD-1");
    assertThat(mapper.holdId(answered("PostTransfer", "{}"))).isNull();
  }

  @Test
  void wal13IsTheTransactionStatusWhoseStatusIsTheCoresTruth() {
    CoreReply held = answered("QueryTransaction", "{\"status\":\"HELD\",\"holdId\":\"HOLD-1\",\"entries\":[]}");

    assertThat(mapper.result(held)).isEqualTo("TransactionStatus");
    assertThat(mapper.txnStatus(held)).isEqualTo("HELD");
    assertThat(mapper.toSagaEvent(held, true, false)).contains(new HoldQueryResult(true));
    assertThat(mapper.toSagaEvent(answered("QueryTransaction", "{\"status\":\"NOT_FOUND\"}"), true, false))
        .contains(new HoldQueryResult(false));
  }

  @Test
  void theReportedBalanceIsReadFromTheAnswer() {
    CoreReply reported = answered("BalanceInquiry",
        "{\"walletId\":\"01711000001\",\"available\":{\"valueMinor\":1200000,\"currency\":\"BDT\"},\"asOf\":\"2026-11-16T08:15:31Z\"}");

    assertThat(mapper.balance(reported)).contains(new Balance(Amount.bdt(1_200_000), Instant.parse("2026-11-16T08:15:31Z")));
    assertThat(mapper.balance(answered("BalanceInquiry", "{\"asOf\":\"2026-11-16T08:15:31Z\"}"))).isEmpty();
    assertThat(mapper.balance(answered("PostTransfer", "{}"))).isEqualTo(Optional.empty());
  }

  @ParameterizedTest
  @CsvSource({
    "WALLET_NOT_FOUND", "CREDIT_WALLET_NOT_FOUND", "ACCOUNT_BLOCKED", "CREDIT_ACCOUNT_BLOCKED",
    "SAME_WALLET", "INVALID_AMOUNT", "LIMIT_EXCEEDED_PER_TXN",
    "LIMIT_EXCEEDED_DAILY", "INSUFFICIENT_BALANCE", "INVALID_COUNTERPARTY", "ACCOUNT_LOCKED"
  })
  void everyCoreReasonHasAReadableMessage(String reason) {
    ErrorInfo error = mapper.error(refused("PostTransfer", 422, "MW-CORE-" + reason));
    assertThat(error.code()).isEqualTo("MW-CORE-" + reason);
    assertThat(error.message()).isNotEqualTo("Rejected by the core");
  }
}
