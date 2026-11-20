package mfs.mw.orchestrator.service.saga;

import mfs.mw.orchestrator.exception.InvalidTransitionException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import org.junit.jupiter.api.Test;

/** The §7.2 table, row by row: every listed transition holds and every other pair is refused. */
class SagaMachineTest {

  private record Key(Status from, SagaEvent event) {}

  private record Expect(Status to, Effect effect) {}

  private static final Map<Key, Expect> TABLE = new LinkedHashMap<>();

  private static void row(Status from, SagaEvent event, Status to, Effect effect) {
    TABLE.put(new Key(from, event), new Expect(to, effect));
  }

  static {
    row(Status.RECEIVED, new ValidationStarted(), Status.VALIDATING, Effect.SEND_VALIDATE);
    row(Status.RECEIVED, new RequestRejected(), Status.REJECTED, Effect.NONE);

    row(Status.VALIDATING, new Validated(false), Status.POSTING, Effect.SEND_POST);
    row(Status.VALIDATING, new Validated(true), Status.HOLDING, Effect.SEND_HOLD);
    // inbound money (ADD_MONEY_BANK, ticket 23): nothing to hold, the partner is asked first
    row(Status.VALIDATING, new Validated(false, true), Status.PARTNER_PENDING, Effect.SEND_PARTNER_REQUEST);
    // REMIT_IN (ticket 119b): inbound money without a partner leg credits the wallet once validated
    row(Status.VALIDATING, new CreditValidated(), Status.COMMITTING, Effect.SEND_COMMIT);
    row(Status.VALIDATING, new ValidationRejected(), Status.REJECTED, Effect.NONE);
    row(Status.VALIDATING, new DeadlineExpired(), Status.FAILED, Effect.NONE);
    // BALANCE (ADR D26): one BalanceInquiry in VALIDATING, answered COMPLETED or FAILED; no money moves
    row(Status.RECEIVED, new BalanceInquiryStarted(), Status.VALIDATING, Effect.SEND_BALANCE_INQUIRY);
    row(Status.VALIDATING, new BalanceReported(), Status.COMPLETED, Effect.NONE);
    row(Status.VALIDATING, new BalanceInquiryRejected(), Status.FAILED, Effect.NONE);

    row(Status.HOLDING, new HoldPlaced(), Status.PARTNER_PENDING, Effect.SEND_PARTNER_REQUEST);
    row(Status.HOLDING, new HoldRejected(), Status.REJECTED, Effect.NONE);
    row(Status.HOLDING, new DeadlineExpired(), Status.HOLDING, Effect.SEND_QUERY);
    row(Status.HOLDING, new HoldQueryResult(true), Status.RELEASING, Effect.SEND_RELEASE);
    row(Status.HOLDING, new HoldQueryResult(false), Status.REJECTED, Effect.NONE);
    // the core released the hold on expiry before the partner was asked (ticket 95c)
    row(Status.HOLDING, new HoldExpired(false), Status.RELEASING, Effect.SEND_RELEASE);

    row(Status.POSTING, new PostSucceeded(), Status.COMPLETED, Effect.NONE);
    row(Status.POSTING, new PostRejected(), Status.REJECTED, Effect.NONE);
    row(Status.POSTING, new DeadlineExpired(), Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);

    row(Status.PARTNER_PENDING, new PartnerSucceeded(), Status.COMMITTING, Effect.SEND_COMMIT);
    row(Status.PARTNER_PENDING, new PartnerFailed(), Status.RELEASING, Effect.SEND_RELEASE);
    // no hold (ADD_MONEY_BANK): a refusal leaves nothing to release
    row(Status.PARTNER_PENDING, new PartnerFailed(false), Status.FAILED, Effect.NONE);
    row(Status.PARTNER_PENDING, new PartnerUnknown(), Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);
    row(Status.PARTNER_PENDING, new DeadlineExpired(), Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);
    // waiting on the partner when the hold runs out: confirm the release (ticket 95c)
    row(Status.PARTNER_PENDING, new HoldExpired(false), Status.RELEASING, Effect.SEND_RELEASE);

    // 7.4: a held transaction commits or releases its hold; a posting one (nothing held) ends
    row(Status.UNKNOWN, new InquirySucceeded(true), Status.COMMITTING, Effect.SEND_COMMIT);
    row(Status.UNKNOWN, new InquiryFailed(true), Status.RELEASING, Effect.SEND_RELEASE);
    row(Status.UNKNOWN, new InquirySucceeded(false), Status.COMPLETED, Effect.NONE);
    row(Status.UNKNOWN, new InquiryFailed(false), Status.FAILED, Effect.NONE);
    row(Status.UNKNOWN, new InquiryExhausted(), Status.MANUAL_REVIEW, Effect.NONE);
    row(Status.UNKNOWN, new DeadlineExpired(), Status.UNKNOWN, Effect.SEND_INQUIRY);

    row(Status.COMMITTING, new CommitAcknowledged(), Status.COMPLETED, Effect.NONE);
    row(Status.COMMITTING, new CommitRefused(), Status.MANUAL_REVIEW, Effect.NONE);
    // a partner leg was paid (ticket 96b): compensate it before asking about the hold
    row(Status.COMMITTING, new CommitRefused(true), Status.REVERSING, Effect.SEND_PARTNER_REVERSE);
    row(Status.COMMITTING, new DeadlineExpired(), Status.COMMITTING, Effect.SEND_COMMIT);
    // an operator's forced credit (ticket 37) asks the core first: credit only what is not credited
    row(Status.COMMITTING, new CreditCheckResult(CreditCheckResult.Credit.CREDITED), Status.COMPLETED, Effect.NONE);
    row(Status.COMMITTING, new CreditCheckResult(CreditCheckResult.Credit.NOT_CREDITED), Status.COMMITTING, Effect.SEND_CREDIT);
    row(Status.COMMITTING, new CreditCheckResult(CreditCheckResult.Credit.CONFLICT), Status.MANUAL_REVIEW, Effect.NONE);
    // the partner already succeeded and the hold can no longer be committed (ticket 95c)
    row(Status.COMMITTING, new HoldExpired(true), Status.MANUAL_REVIEW, Effect.NONE);

    row(Status.RELEASING, new ReleaseAcknowledged(), Status.FAILED, Effect.NONE);
    row(Status.RELEASING, new ReleaseRefused(), Status.MANUAL_REVIEW, Effect.NONE);
    row(Status.RELEASING, new DeadlineExpired(), Status.RELEASING, Effect.SEND_RELEASE);

    row(Status.REVERSING, new ReversalAcknowledged(), Status.REVERSED, Effect.NONE);
    row(Status.REVERSING, new ReversalFailed(), Status.MANUAL_REVIEW, Effect.NONE);
    row(Status.REVERSING, new DeadlineExpired(), Status.REVERSING, Effect.SEND_REVERSE);
    // resends stop at reversing-timeout; once the last one has expired the core is asked (S1)
    row(Status.REVERSING, new ReversalResendsStopped(), Status.REVERSING, Effect.NONE);
    row(Status.REVERSING, new ReversalUnconfirmed(), Status.REVERSING, Effect.SEND_REVERSAL_QUERY);
    row(Status.REVERSING, new ReversalQueryResult(true), Status.REVERSED, Effect.NONE);
    row(Status.REVERSING, new ReversalQueryResult(false), Status.MANUAL_REVIEW, Effect.NONE);
    // refused commit, after the partner confirms REVERSE (ticket 96b): ask about the hold
    row(Status.REVERSING, new PartnerReverseConfirmed(), Status.REVERSING, Effect.SEND_HOLD_QUERY);
    row(Status.REVERSING, new PartnerReverseRefused(), Status.FAILED, Effect.NONE);
    // completed TOPUP or bill payment (ticket 96c): partner confirmed, so reverse at the core
    row(Status.REVERSING, new PartnerReverseConfirmed(PartnerReverseConfirmed.Follow.CORE), Status.REVERSING, Effect.SEND_CORE_REVERSE);
    // the partner refused to undo a completed sale: the posting stays, operations decide
    row(Status.REVERSING, new PartnerReverseRefused(PartnerReverseRefused.End.MANUAL_REVIEW), Status.MANUAL_REVIEW, Effect.NONE);
    row(Status.REVERSING, new HoldCheckResult(HoldCheckResult.Hold.EXISTS), Status.REVERSING, Effect.SEND_COMPENSATING_RELEASE);
    row(Status.REVERSING, new HoldCheckResult(HoldCheckResult.Hold.GONE), Status.REVERSED, Effect.NONE);
    row(Status.REVERSING, new HoldCheckResult(HoldCheckResult.Hold.UNCLEAR), Status.MANUAL_REVIEW, Effect.NONE);
    row(Status.REVERSING, new CompensatingReleaseRetry(), Status.REVERSING, Effect.SEND_COMPENSATING_RELEASE);
    row(Status.REVERSING, new CompensatingReleaseAcknowledged(), Status.REVERSED, Effect.NONE);
    row(Status.REVERSING, new CompensatingReleaseRefused(), Status.MANUAL_REVIEW, Effect.NONE);

    row(Status.COMPLETED, new ReversalRequested(), Status.REVERSING, Effect.SEND_REVERSE);
    // a completed TOPUP or bill payment undoes the partner before the core (ticket 96c)
    row(Status.COMPLETED, new ReversalRequested(true), Status.REVERSING, Effect.SEND_PARTNER_REVERSE);

    row(Status.MANUAL_REVIEW, new ManualCommit(), Status.COMMITTING, Effect.SEND_COMMIT);
    row(Status.MANUAL_REVIEW, new ManualRelease(), Status.RELEASING, Effect.SEND_RELEASE);
    // a transaction handed over from REVERSING whose reversal the core applied after all
    row(Status.MANUAL_REVIEW, new ReversalAcknowledged(), Status.REVERSED, Effect.NONE);
    row(Status.MANUAL_REVIEW, new ReversalQueryResult(true), Status.REVERSED, Effect.NONE);

    // a merchant refund is its own record (ticket 116b): core reversal, then COMPLETED or REJECTED
    row(Status.RECEIVED, new RefundStarted(), Status.REVERSING, Effect.SEND_REVERSE);
    // ticket 115b: the merchant system VALIDATE before the core
    row(Status.RECEIVED, new MerchantValidationStarted(), Status.VALIDATING, Effect.SEND_MERCHANT_VALIDATE);
    row(Status.VALIDATING, new MerchantValidated(), Status.VALIDATING, Effect.SEND_VALIDATE);
    row(Status.VALIDATING, new MerchantRejected(), Status.FAILED, Effect.NONE);
    row(Status.REVERSING, new RefundConfirmed(), Status.COMPLETED, Effect.NONE);
    row(Status.REVERSING, new RefundRefused(), Status.REJECTED, Effect.NONE);
    row(Status.MANUAL_REVIEW, new RefundConfirmed(), Status.COMPLETED, Effect.NONE);

    // an operator's status inquiry (ticket 37): one inquiry now, the state stays
    row(Status.UNKNOWN, new ManualInquiry(), Status.UNKNOWN, Effect.SEND_INQUIRY);
    // the partner has not succeeded: the expired hold is confirmed released (ticket 95c)
    row(Status.UNKNOWN, new HoldExpired(false), Status.RELEASING, Effect.SEND_RELEASE);
    row(Status.MANUAL_REVIEW, new ManualInquiry(), Status.MANUAL_REVIEW, Effect.SEND_INQUIRY);
  }

  private static List<SagaEvent> allEvents() {
    return List.of(
        new ValidationStarted(), new RequestRejected(), new BalanceInquiryStarted(), new BalanceReported(), new BalanceInquiryRejected(), new Validated(false), new Validated(true), new Validated(false, true), new CreditValidated(),
        new ValidationRejected(), new HoldPlaced(), new HoldRejected(), new HoldQueryResult(true),
        new HoldQueryResult(false), new PostSucceeded(), new PostRejected(), new PartnerSucceeded(),
        new PartnerFailed(), new PartnerFailed(false), new PartnerUnknown(), new InquirySucceeded(true), new InquirySucceeded(false),
        new InquiryFailed(true), new InquiryFailed(false), new InquiryExhausted(),
        new CommitAcknowledged(), new CommitRefused(), new CommitRefused(true), new ReleaseAcknowledged(), new ReleaseRefused(),
        new PartnerReverseConfirmed(), new PartnerReverseConfirmed(PartnerReverseConfirmed.Follow.CORE),
        new PartnerReverseRefused(), new PartnerReverseRefused(PartnerReverseRefused.End.MANUAL_REVIEW),
        new HoldCheckResult(HoldCheckResult.Hold.EXISTS), new HoldCheckResult(HoldCheckResult.Hold.GONE),
        new HoldCheckResult(HoldCheckResult.Hold.UNCLEAR),
        new CompensatingReleaseRetry(), new CompensatingReleaseAcknowledged(), new CompensatingReleaseRefused(),
        new ReversalRequested(), new ReversalRequested(true), new ReversalAcknowledged(), new ReversalFailed(),
        new RefundStarted(), new RefundConfirmed(), new RefundRefused(),
        new MerchantValidationStarted(), new MerchantValidated(), new MerchantRejected(),
        new ReversalResendsStopped(), new ReversalUnconfirmed(), new ReversalQueryResult(true), new ReversalQueryResult(false),
        new ManualCommit(), new ManualRelease(), new ManualInquiry(), new DeadlineExpired(),
        new HoldExpired(false), new HoldExpired(true),
        new CreditCheckResult(CreditCheckResult.Credit.CREDITED), new CreditCheckResult(CreditCheckResult.Credit.NOT_CREDITED),
        new CreditCheckResult(CreditCheckResult.Credit.CONFLICT));
  }

  @Test
  void modelsAllFourteenStatesAndFourTerminalOnes() {
    assertThat(Status.values()).hasSize(14);
    assertThat(List.of(Status.values()).stream().filter(Status::terminal))
        .containsExactlyInAnyOrder(Status.COMPLETED, Status.FAILED, Status.REJECTED, Status.REVERSED);
  }

  @Test
  void everyListedTransitionIsTakenWithItsEffect() {
    assertThat(TABLE).hasSize(76);
    TABLE.forEach(
        (key, expect) -> {
          Transition t = SagaMachine.apply(key.from(), key.event());
          assertThat(t.from()).as("%s %s", key.from(), key.event()).isEqualTo(key.from());
          assertThat(t.to()).as("%s %s", key.from(), key.event()).isEqualTo(expect.to());
          assertThat(t.effect()).as("%s %s", key.from(), key.event()).isEqualTo(expect.effect());
        });
  }

  @Test
  void everyOtherStatusEventPairIsRefused() {
    List<String> wronglyAccepted = new ArrayList<>();
    int refused = 0;
    for (Status from : Status.values()) {
      for (SagaEvent event : allEvents()) {
        if (TABLE.containsKey(new Key(from, event))) {
          continue;
        }
        try {
          SagaMachine.apply(from, event);
          wronglyAccepted.add(from + " " + event);
        } catch (InvalidTransitionException e) {
          assertThat(e.from()).isEqualTo(from);
          assertThat(e.event()).isEqualTo(event);
          refused++;
        }
      }
    }
    assertThat(wronglyAccepted).isEmpty();
    assertThat(refused).isEqualTo(Status.values().length * allEvents().size() - TABLE.size());
  }

  @Test
  void terminalStatesAcceptNothingExceptReversalFromCompleted() {
    for (Status s : List.of(Status.FAILED, Status.REJECTED, Status.REVERSED)) {
      assertThatThrownBy(() -> SagaMachine.apply(s, new DeadlineExpired()))
          .isInstanceOf(InvalidTransitionException.class);
    }
    assertThat(SagaMachine.apply(Status.COMPLETED, new ReversalRequested()).to())
        .isEqualTo(Status.REVERSING);
  }

  @Test
  void selfTransitionsAreFlaggedAsNotChangingState() {
    assertThat(SagaMachine.apply(Status.COMMITTING, new DeadlineExpired()).changesState()).isFalse();
    assertThat(SagaMachine.apply(Status.POSTING, new DeadlineExpired()).changesState()).isTrue();
  }
}
