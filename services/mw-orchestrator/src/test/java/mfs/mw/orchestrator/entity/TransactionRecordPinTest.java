package mfs.mw.orchestrator.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.dto.EncryptedPin;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import mfs.mw.orchestrator.service.saga.SagaMachine;
import mfs.mw.orchestrator.service.saga.Status;
import mfs.mw.orchestrator.service.saga.Transition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * PIN ciphertext lifetime (spec 18.3, ticket 50): it may sit on the transaction row while the core
 * may still need it, and is wiped on every transition into COMPLETED, REJECTED, FAILED, REVERSED or
 * MANUAL_REVIEW. {@code SagaEngine.fire} persists exactly {@code moved(to, ...)}, so every path of
 * the state machine is covered here.
 */
class TransactionRecordPinTest {

  private static final EncryptedPin PIN = new EncryptedPin("kid-test", "FAKE-CIPHERTEXT==");
  private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

  /** One instance of every saga event, each boolean variant included. */
  private static final List<SagaEvent> EVENTS =
      List.of(
          new ValidationStarted(), new RequestRejected(), new Validated(false, false),
          new Validated(true, false), new Validated(false, true), new Validated(true, true),
          new ValidationRejected(), new HoldPlaced(), new HoldRejected(), new HoldQueryResult(true),
          new HoldQueryResult(false), new PostSucceeded(), new PostRejected(),
          new PartnerSucceeded(), new PartnerFailed(true), new PartnerFailed(false),
          new PartnerUnknown(), new InquirySucceeded(true), new InquirySucceeded(false),
          new InquiryFailed(true), new InquiryFailed(false), new InquiryExhausted(),
          new CommitAcknowledged(), new CommitRefused(), new ReleaseAcknowledged(),
          new ReleaseRefused(), new ReversalRequested(), new ReversalAcknowledged(),
          new ReversalFailed(), new ReversalResendsStopped(), new ReversalUnconfirmed(),
          new ReversalQueryResult(true), new ReversalQueryResult(false), new ManualCommit(),
          new ManualRelease(), new DeadlineExpired());

  /** Every transition the state machine allows. */
  static List<Transition> transitions() {
    List<Transition> all = new ArrayList<>();
    for (Status from : Status.values()) {
      for (SagaEvent e : EVENTS) {
        try {
          all.add(SagaMachine.apply(from, e));
        } catch (InvalidTransitionException refused) {
          // not a path
        }
      }
    }
    return all;
  }

  static List<Transition> pathsThatWipe() {
    return transitions().stream().filter(t -> wipes(t.to())).toList();
  }

  static List<Transition> pathsThatKeep() {
    return transitions().stream().filter(t -> !wipes(t.to())).toList();
  }

  private static boolean wipes(Status s) {
    return s == Status.COMPLETED
        || s == Status.REJECTED
        || s == Status.FAILED
        || s == Status.REVERSED
        || s == Status.MANUAL_REVIEW;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pathsThatWipe")
  void thePinCiphertextIsNullAfterEveryTerminalOrManualReviewTransition(Transition t) {
    assertThat(record(t.from()).moved(t.to(), NOW, null).encryptedPin()).isNull();
  }

  /** Before the end the core may still need it (PostTransfer follows ValidateTransaction). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("pathsThatKeep")
  void thePinCiphertextStaysUntilTheTransactionEnds(Transition t) {
    assertThat(record(t.from()).moved(t.to(), NOW, null).encryptedPin()).isEqualTo(PIN);
  }

  @Test
  void everyEndStateIsReachedByAtLeastOnePath() {
    assertThat(pathsThatWipe())
        .extracting(Transition::to)
        .contains(
            Status.COMPLETED, Status.REJECTED, Status.FAILED, Status.REVERSED, Status.MANUAL_REVIEW);
  }

  private static TransactionRecord record(Status status) {
    return new TransactionRecord(
        UUID.fromString("0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e0f"), NOW, NOW, null, status, null,
        null, null, null, null, PIN, "hold-1", null, null);
  }
}
