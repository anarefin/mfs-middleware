package mfs.mw.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class CoreEventMapperTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private final CoreEventMapper mapper = new CoreEventMapper();

  private CoreEvent event(String type, String outcome, String reason, String payload) {
    return new CoreEvent(
        1, type, "evt-1", "txn:cmd", "txn", "CORE-1", Instant.parse("2026-11-03T08:15:30Z"),
        outcome, reason, null, json.readTree(payload));
  }

  @ParameterizedTest
  @CsvSource({
    "TransactionRejected,ValidationRejected",
    "TransferPosted,PostSucceeded",
    "TransferRejected,PostRejected",
    "FundsHeld,HoldPlaced",
    "HoldRejected,HoldRejected",
    "HoldCommitted,CommitAcknowledged",
    "HoldReleased,ReleaseAcknowledged",
    "TransactionReversed,ReversalAcknowledged",
    "ReversalRejected,ReversalFailed",
    // CreditWallet is the commit step of inbound money (ADD_MONEY_BANK, ticket 23)
    "WalletCredited,CommitAcknowledged",
    "CreditRejected,CommitRefused"
  })
  void coreResultsBecomeSagaEvents(String eventType, String expected) {
    Optional<SagaEvent> got = mapper.toSagaEvent(event(eventType, "SUCCESS", null, "{}"), false);
    assertThat(got).isPresent();
    assertThat(got.get().getClass().getSimpleName()).isEqualTo(expected);
  }

  @Test
  void validatedCarriesTheFlowsHoldDecision() {
    assertThat(mapper.toSagaEvent(event("TransactionValidated", "SUCCESS", null, "{}"), true))
        .contains(new Validated(true));
    assertThat(mapper.toSagaEvent(event("TransactionValidated", "SUCCESS", null, "{}"), false))
        .contains(new Validated(false));
    assertThat(mapper.toSagaEvent(event("TransactionValidated", "SUCCESS", null, "{}"), false, true))
        .contains(new Validated(false, true));
  }

  @Test
  void eventTypesWithoutAFlowYetAreNotMapped() {
    assertThat(mapper.toSagaEvent(event("ComplianceActionApplied", "SUCCESS", null, "{}"), false)).isEmpty();
    assertThat(mapper.toSagaEvent(event("SomethingNew", "SUCCESS", null, "{}"), false)).isEmpty();
  }

  @Test
  void feeIsReadFromTheValidatedPayload() {
    CoreEvent e =
        event("TransactionValidated", "SUCCESS", null, "{\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"}}");
    assertThat(mapper.feeMinor(e)).isEqualTo(500L);
    assertThat(mapper.feeMinor(event("TransferPosted", "SUCCESS", null, "{}"))).isNull();
  }

  @Test
  void aCoreReasonBecomesAnMwCoreErrorWithBothLanguages() {
    ErrorInfo error =
        mapper.error(event("TransactionRejected", "FAILURE", "INSUFFICIENT_BALANCE", "{}"));
    assertThat(error.code()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(error.message()).isEqualTo("Insufficient balance");
    assertThat(error.messageBn()).isNotBlank();
    assertThat(error.retryable()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "WALLET_NOT_FOUND", "CREDIT_WALLET_NOT_FOUND", "ACCOUNT_BLOCKED", "CREDIT_ACCOUNT_BLOCKED",
    "SAME_WALLET", "INVALID_PIN", "INVALID_PIN_BLOCK", "INVALID_AMOUNT", "LIMIT_EXCEEDED_PER_TXN",
    "LIMIT_EXCEEDED_DAILY", "INSUFFICIENT_BALANCE", "INVALID_COUNTERPARTY", "ACCOUNT_LOCKED"
  })
  void everyCoreMockReasonHasAReadableMessage(String reason) {
    ErrorInfo error = mapper.error(event("TransferRejected", "FAILURE", reason, "{}"));
    assertThat(error.code()).isEqualTo("MW-CORE-" + reason);
    assertThat(error.message()).isNotEqualTo("Rejected by the core");
  }

  @Test
  void anUnknownOrMalformedReasonStaysWithinTheCodePattern() {
    assertThat(mapper.error(event("TransferRejected", "FAILURE", "weird reason!", "{}")).code())
        .isEqualTo("MW-CORE-UNKNOWN");
    assertThat(mapper.error(event("TransferRejected", "FAILURE", null, "{}")).code())
        .isEqualTo("MW-CORE-UNKNOWN");
    assertThat(mapper.error(event("TransferRejected", "FAILURE", "NEW_RULE_7", "{}")))
        .satisfies(
            e -> {
              assertThat(e.code()).isEqualTo("MW-CORE-NEW_RULE_7");
              assertThat(e.message()).isEqualTo("Rejected by the core");
            });
  }
}
