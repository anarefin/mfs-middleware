package mfs.mw.orchestrator.service.saga;

import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;

/**
 * The transaction state machine of spec 7.2, plain Java and stateless: given a state and an event
 * it returns the transition or refuses with {@link InvalidTransitionException}. All 14 states and
 * their transitions are modelled, including those of flows that are not wired yet.
 */
public final class SagaMachine {

  private SagaMachine() {}

  public static Transition apply(Status from, SagaEvent event) {
    Transition t = lookup(from, event);
    if (t == null) {
      throw new InvalidTransitionException(from, event);
    }
    return t;
  }

  private static Transition go(Status from, Status to, Effect effect) {
    return new Transition(from, to, effect);
  }

  private static Transition lookup(Status from, SagaEvent event) {
    return switch (from) {
      case RECEIVED ->
          switch (event) {
            case ValidationStarted e -> go(from, Status.VALIDATING, Effect.SEND_VALIDATE);
            case RequestRejected e -> go(from, Status.REJECTED, Effect.NONE);
            default -> null;
          };
      case VALIDATING ->
          switch (event) {
            case Validated e when e.callPartner() -> go(from, Status.PARTNER_PENDING, Effect.SEND_PARTNER_REQUEST);
            case Validated e when e.needsHold() -> go(from, Status.HOLDING, Effect.SEND_HOLD);
            case Validated e -> go(from, Status.POSTING, Effect.SEND_POST);
            case ValidationRejected e -> go(from, Status.REJECTED, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.FAILED, Effect.NONE);
            default -> null;
          };
      case HOLDING ->
          switch (event) {
            case HoldPlaced e -> go(from, Status.PARTNER_PENDING, Effect.SEND_PARTNER_REQUEST);
            case HoldRejected e -> go(from, Status.REJECTED, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.HOLDING, Effect.SEND_QUERY);
            case HoldQueryResult e when e.held() -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
            case HoldQueryResult e -> go(from, Status.REJECTED, Effect.NONE);
            default -> null;
          };
      case POSTING ->
          switch (event) {
            case PostSucceeded e -> go(from, Status.COMPLETED, Effect.NONE);
            case PostRejected e -> go(from, Status.REJECTED, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);
            default -> null;
          };
      case PARTNER_PENDING ->
          switch (event) {
            case PartnerSucceeded e -> go(from, Status.COMMITTING, Effect.SEND_COMMIT);
            case PartnerFailed e when !e.held() -> go(from, Status.FAILED, Effect.NONE);
            case PartnerFailed e -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
            case PartnerUnknown e -> go(from, Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);
            case DeadlineExpired e -> go(from, Status.UNKNOWN, Effect.SCHEDULE_INQUIRY);
            default -> null;
          };
      case UNKNOWN ->
          switch (event) {
            case InquirySucceeded e when e.held() -> go(from, Status.COMMITTING, Effect.SEND_COMMIT);
            case InquirySucceeded e -> go(from, Status.COMPLETED, Effect.NONE);
            case InquiryFailed e when e.held() -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
            case InquiryFailed e -> go(from, Status.FAILED, Effect.NONE);
            case InquiryExhausted e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.UNKNOWN, Effect.SEND_INQUIRY);
            case ManualInquiry e -> go(from, Status.UNKNOWN, Effect.SEND_INQUIRY);
            default -> null;
          };
      case COMMITTING ->
          switch (event) {
            case CommitAcknowledged e -> go(from, Status.COMPLETED, Effect.NONE);
            // a definite refusal; resending the same commandId gets the same answer (7.4 conflict)
            case CommitRefused e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.COMMITTING, Effect.SEND_COMMIT);
            // a forced credit asks the core first (ticket 37): never credit a txn twice
            case CreditCheckResult e when e.credit() == CreditCheckResult.Credit.CREDITED ->
                go(from, Status.COMPLETED, Effect.NONE);
            case CreditCheckResult e when e.credit() == CreditCheckResult.Credit.NOT_CREDITED ->
                go(from, Status.COMMITTING, Effect.SEND_CREDIT);
            case CreditCheckResult e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            default -> null;
          };
      case RELEASING ->
          switch (event) {
            case ReleaseAcknowledged e -> go(from, Status.FAILED, Effect.NONE);
            case ReleaseRefused e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
            default -> null;
          };
      case REVERSING ->
          switch (event) {
            case ReversalAcknowledged e -> go(from, Status.REVERSED, Effect.NONE);
            case ReversalFailed e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.REVERSING, Effect.SEND_REVERSE);
            case ReversalResendsStopped e -> go(from, Status.REVERSING, Effect.NONE);
            case ReversalUnconfirmed e -> go(from, Status.REVERSING, Effect.SEND_REVERSAL_QUERY);
            case ReversalQueryResult e when e.reversed() -> go(from, Status.REVERSED, Effect.NONE);
            case ReversalQueryResult e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            default -> null;
          };
      case COMPLETED ->
          event instanceof ReversalRequested
              ? go(from, Status.REVERSING, Effect.SEND_REVERSE)
              : null;
      case MANUAL_REVIEW ->
          switch (event) {
            case ManualCommit e -> go(from, Status.COMMITTING, Effect.SEND_COMMIT);
            case ManualRelease e -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
            case ManualInquiry e -> go(from, Status.MANUAL_REVIEW, Effect.SEND_INQUIRY);
            // only a transaction handed over from REVERSING has a reversal the core can confirm
            // (TransactionReversed answers a ReverseTransaction only): the core's truth wins
            case ReversalAcknowledged e -> go(from, Status.REVERSED, Effect.NONE);
            case ReversalQueryResult e when e.reversed() -> go(from, Status.REVERSED, Effect.NONE);
            default -> null;
          };
      case FAILED, REJECTED, REVERSED -> null;
    };
  }
}
