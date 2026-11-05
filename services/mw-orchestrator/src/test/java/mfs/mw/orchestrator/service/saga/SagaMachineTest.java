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
    row(Status.VALIDATING, new ValidationRejected(), Status.REJECTED, Effect.NONE);
    row(Status.VALIDATING, new DeadlineExpired(), Status.FAILED, Effect.NONE);

    row(Status.HOLDING, new HoldPlaced(), Status.PARTNER_PENDING, Effect.SEND_PARTNER_REQUEST);
    row(Status.HOLDING, new HoldRejected(), Status.REJECTED, Effect.NONE);
    row(Status.HOLDING, new DeadlineExpired(), Status.HOLDING, Effect.SEND_QUERY);
    row(Status.HOLDING, new HoldQueryResult(true), Status.RELEASING, Effect.SEND_RELEASE);
    row(Status.HOLDING, new HoldQueryResult(false), Status.REJECTED, Effect.NONE);

    row(Status.POSTING, new PostSucceeded(), Status.COMPLETED, Effect.NONE);
    row(Status.POSTING, new PostRejected(), Status.REJECTED, Effect.NONE);
    row(Status.POSTING, new DeadlineExpired(), Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);

    row(Status.PARTNER_PENDING, new PartnerSucceeded(), Status.COMMITTING, Effect.SEND_COMMIT);
    row(Status.PARTNER_PENDING, new PartnerFailed(), Status.RELEASING, Effect.SEND_RELEASE);
    // no hold (ADD_MONEY_BANK): a refusal leaves nothing to release
    row(Status.PARTNER_PENDING, new PartnerFailed(false), Status.FAILED, Effect.NONE);
    row(Status.PARTNER_PENDING, new PartnerUnknown(), Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);
    row(Status.PARTNER_PENDING, new DeadlineExpired(), Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);

    // 7.4: a held transaction commits or releases its hold; a posting one (nothing held) ends
    row(Status.UNKNOWN, new InquirySucceeded(true), Status.COMMITTING, Effect.SEND_COMMIT);
    row(Status.UNKNOWN, new InquiryFailed(true), Status.RELEASING, Effect.SEND_RELEASE);
    row(Status.UNKNOWN, new InquirySucceeded(false), Status.COMPLETED, Effect.NONE);
    row(Status.UNKNOWN, new InquiryFailed(false), Status.FAILED, Effect.NONE);
    row(Status.UNKNOWN, new InquiryExhausted(), Status.MANUAL_REVIEW, Effect.NONE);
    row(Status.UNKNOWN, new DeadlineExpired(), Status.UNKNOWN, Effect.SEND_INQUIRY);

    row(Status.COMMITTING, new CommitAcknowledged(), Status.COMPLETED, Effect.NONE);
    row(Status.COMMITTING, new CommitRefused(), Status.MANUAL_REVIEW, Effect.NONE);
    row(Status.COMMITTING, new DeadlineExpired(), Status.COMMITTING, Effect.SEND_COMMIT);
    // an operator's forced credit (ticket 37) asks the core first: credit only what is not credited
    row(Status.COMMITTING, new CreditCheckResult(CreditCheckResult.Credit.CREDITED), Status.COMPLETED, Effect.NONE);
    row(Status.COMMITTING, new CreditCheckResult(CreditCheckResult.Credit.NOT_CREDITED), Status.COMMITTING, Effect.SEND_CREDIT);
    row(Status.COMMITTING, new CreditCheckResult(CreditCheckResult.Credit.CONFLICT), Status.MANUAL_REVIEW, Effect.NONE);

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

    row(Status.COMPLETED, new ReversalRequested(), Status.REVERSING, Effect.SEND_REVERSE);

    row(Status.MANUAL_REVIEW, new ManualCommit(), Status.COMMITTING, Effect.SEND_COMMIT);
    row(Status.MANUAL_REVIEW, new ManualRelease(), Status.RELEASING, Effect.SEND_RELEASE);
    // a transaction handed over from REVERSING whose reversal the core applied after all
    row(Status.MANUAL_REVIEW, new ReversalAcknowledged(), Status.REVERSED, Effect.NONE);
    row(Status.MANUAL_REVIEW, new ReversalQueryResult(true), Status.REVERSED, Effect.NONE);

    // an operator's status inquiry (ticket 37): one inquiry now, the state stays
    row(Status.UNKNOWN, new ManualInquiry(), Status.UNKNOWN, Effect.SEND_INQUIRY);
    row(Status.MANUAL_REVIEW, new ManualInquiry(), Status.MANUAL_REVIEW, Effect.SEND_INQUIRY);
  }

  private static List<SagaEvent> allEvents() {
    return List.of(
        new ValidationStarted(), new RequestRejected(), new Validated(false), new Validated(true), new Validated(false, true),
        new ValidationRejected(), new HoldPlaced(), new HoldRejected(), new HoldQueryResult(true),
        new HoldQueryResult(false), new PostSucceeded(), new PostRejected(), new PartnerSucceeded(),
        new PartnerFailed(), new PartnerFailed(false), new PartnerUnknown(), new InquirySucceeded(true), new InquirySucceeded(false),
        new InquiryFailed(true), new InquiryFailed(false), new InquiryExhausted(),
        new CommitAcknowledged(), new CommitRefused(), new ReleaseAcknowledged(), new ReleaseRefused(),
        new ReversalRequested(), new ReversalAcknowledged(), new ReversalFailed(),
        new ReversalResendsStopped(), new ReversalUnconfirmed(), new ReversalQueryResult(true), new ReversalQueryResult(false),
        new ManualCommit(), new ManualRelease(), new ManualInquiry(), new DeadlineExpired(),
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
    assertThat(TABLE).hasSize(49);
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
