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
            // ticket 115b: the merchant system is asked first, still VALIDATING
            case MerchantValidationStarted e -> go(from, Status.VALIDATING, Effect.SEND_MERCHANT_VALIDATE);
            // BALANCE (ADR D26): VALIDATING waits for the BalanceInquiry answer (no new status)
            case BalanceInquiryStarted e -> go(from, Status.VALIDATING, Effect.SEND_BALANCE_INQUIRY);
            case RequestRejected e -> go(from, Status.REJECTED, Effect.NONE);
            // a merchant refund (ticket 116b) is its own record: straight to the core reversal
            case RefundStarted e -> go(from, Status.REVERSING, Effect.SEND_REVERSE);
            default -> null;
          };
      case VALIDATING ->
          switch (event) {
            case Validated e when e.callPartner() -> go(from, Status.PARTNER_PENDING, Effect.SEND_PARTNER_REQUEST);
            case Validated e when e.needsHold() -> go(from, Status.HOLDING, Effect.SEND_HOLD);
            case Validated e -> go(from, Status.POSTING, Effect.SEND_POST);
            case ValidationRejected e -> go(from, Status.REJECTED, Effect.NONE);
            case MerchantValidated e -> go(from, Status.VALIDATING, Effect.SEND_VALIDATE);
            case MerchantRejected e -> go(from, Status.FAILED, Effect.NONE);
            case BalanceReported e -> go(from, Status.COMPLETED, Effect.NONE);
            case BalanceInquiryRejected e -> go(from, Status.FAILED, Effect.NONE);
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
            // the core released the hold before the partner was asked (ticket 95c)
            case HoldExpired e when !e.partnerSucceeded() -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
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
            // ADD_MONEY_CARD (ticket 118b): session opened, then the 3-D Secure webhook. The capture
            // (PAY) carries the webhook's transient token, so the card service sends it, not an effect
            case CardSessionCreated e -> go(from, Status.PARTNER_PENDING, Effect.NONE);
            case CardAuthenticated e -> go(from, Status.PARTNER_PENDING, Effect.NONE);
            // waiting on the partner when the hold runs out (ticket 95c): the partner has not succeeded
            case HoldExpired e when !e.partnerSucceeded() -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
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
            // the partner has not succeeded, so the expired hold is confirmed released (ticket 95c)
            case HoldExpired e when !e.partnerSucceeded() -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
            default -> null;
          };
      case COMMITTING ->
          switch (event) {
            case CommitAcknowledged e -> go(from, Status.COMPLETED, Effect.NONE);
            // a partner leg was paid: compensate it, then ask the core about the hold (ticket 96b)
            case CommitRefused e when e.compensatePartner() ->
                go(from, Status.REVERSING, Effect.SEND_PARTNER_REVERSE);
            // a definite refusal with nothing to compensate; resending gets the same answer
            case CommitRefused e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.COMMITTING, Effect.SEND_COMMIT);
            // a forced credit asks the core first (ticket 37): never credit a txn twice
            case CreditCheckResult e when e.credit() == CreditCheckResult.Credit.CREDITED ->
                go(from, Status.COMPLETED, Effect.NONE);
            case CreditCheckResult e when e.credit() == CreditCheckResult.Credit.NOT_CREDITED ->
                go(from, Status.COMMITTING, Effect.SEND_CREDIT);
            case CreditCheckResult e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            // the partner already succeeded; an expired hold cannot be committed (ticket 95c)
            case HoldExpired e when e.partnerSucceeded() -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
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
            // the refund record itself (ticket 116b): done, or refused with nothing moved
            case RefundConfirmed e -> go(from, Status.COMPLETED, Effect.NONE);
            case RefundRefused e -> go(from, Status.REJECTED, Effect.NONE);
            case DeadlineExpired e -> go(from, Status.REVERSING, Effect.SEND_REVERSE);
            case ReversalResendsStopped e -> go(from, Status.REVERSING, Effect.NONE);
            case ReversalUnconfirmed e -> go(from, Status.REVERSING, Effect.SEND_REVERSAL_QUERY);
            case ReversalQueryResult e when e.reversed() -> go(from, Status.REVERSED, Effect.NONE);
            case ReversalQueryResult e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            // refused commit (ticket 96b): partner REVERSE, then the hold, then REVERSED.
            // a completed top-up or bill payment (ticket 96c): partner REVERSE, then the core.
            case PartnerReverseConfirmed e when e.follow() == PartnerReverseConfirmed.Follow.CORE ->
                go(from, Status.REVERSING, Effect.SEND_CORE_REVERSE);
            case PartnerReverseConfirmed e -> go(from, Status.REVERSING, Effect.SEND_HOLD_QUERY);
            case PartnerReverseRefused e when e.end() == PartnerReverseRefused.End.MANUAL_REVIEW ->
                go(from, Status.MANUAL_REVIEW, Effect.NONE);
            case PartnerReverseRefused e -> go(from, Status.FAILED, Effect.NONE);
            case HoldCheckResult e when e.hold() == HoldCheckResult.Hold.EXISTS ->
                go(from, Status.REVERSING, Effect.SEND_COMPENSATING_RELEASE);
            case HoldCheckResult e when e.hold() == HoldCheckResult.Hold.GONE ->
                go(from, Status.REVERSED, Effect.NONE);
            case HoldCheckResult e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            case CompensatingReleaseRetry e -> go(from, Status.REVERSING, Effect.SEND_COMPENSATING_RELEASE);
            case CompensatingReleaseAcknowledged e -> go(from, Status.REVERSED, Effect.NONE);
            case CompensatingReleaseRefused e -> go(from, Status.MANUAL_REVIEW, Effect.NONE);
            default -> null;
          };
      case COMPLETED ->
          switch (event) {
            case ReversalRequested e when e.partnerFirst() ->
                go(from, Status.REVERSING, Effect.SEND_PARTNER_REVERSE);
            case ReversalRequested e -> go(from, Status.REVERSING, Effect.SEND_REVERSE);
            default -> null;
          };
      case MANUAL_REVIEW ->
          switch (event) {
            case ManualCommit e -> go(from, Status.COMMITTING, Effect.SEND_COMMIT);
            case ManualRelease e -> go(from, Status.RELEASING, Effect.SEND_RELEASE);
            case ManualInquiry e -> go(from, Status.MANUAL_REVIEW, Effect.SEND_INQUIRY);
            // only a transaction handed over from REVERSING has a reversal the core can confirm
            // (TransactionReversed answers a ReverseTransaction only): the core's truth wins
            case ReversalAcknowledged e -> go(from, Status.REVERSED, Effect.NONE);
            case ReversalQueryResult e when e.reversed() -> go(from, Status.REVERSED, Effect.NONE);
            // a refund handed over unconfirmed that the core did book (ticket 116b)
            case RefundConfirmed e -> go(from, Status.COMPLETED, Effect.NONE);
            default -> null;
          };
      case FAILED, REJECTED, REVERSED -> null;
    };
  }
}
