package mfs.mw.orchestrator.service.saga;

/** Inputs of the state machine; payload data stays with the service, the machine only needs the facts. */
public sealed interface SagaEvent {

  record ValidationStarted() implements SagaEvent {}

  record RequestRejected() implements SagaEvent {}

  /**
   * The core accepted ValidateTransaction; {@code needsHold} comes from the flow of the txn type.
   * {@code callPartner}: inbound money (ADD_MONEY_BANK, ticket 23) holds nothing and asks the
   * partner straight away.
   */
  record Validated(boolean needsHold, boolean callPartner) implements SagaEvent {

    public Validated(boolean needsHold) {
      this(needsHold, false);
    }
  }

  record ValidationRejected() implements SagaEvent {}

  record HoldPlaced() implements SagaEvent {}

  record HoldRejected() implements SagaEvent {}

  /** Answer to the QueryTransaction sent after a HOLDING timeout. */
  record HoldQueryResult(boolean held) implements SagaEvent {}

  record PostSucceeded() implements SagaEvent {}

  record PostRejected() implements SagaEvent {}

  record PartnerSucceeded() implements SagaEvent {}

  /**
   * The partner refused. {@code held}: the transaction holds funds to release; false for inbound
   * money (ADD_MONEY_BANK), where nothing moved and the transaction simply fails.
   */
  record PartnerFailed(boolean held) implements SagaEvent {

    public PartnerFailed() {
      this(true);
    }
  }

  record PartnerUnknown() implements SagaEvent {}

  /**
   * Status inquiry (spec 7.4) found the original call done. {@code held}: a commit step follows
   * (the partner leg succeeded: commit the hold, or for inbound money credit the wallet);
   * otherwise the core posted it (completed).
   */
  record InquirySucceeded(boolean held) implements SagaEvent {}

  /**
   * Status inquiry found the original call refused or confirmed it never arrived. {@code held}:
   * release the hold; otherwise nothing was posted (failed).
   */
  record InquiryFailed(boolean held) implements SagaEvent {}

  /** The inquiry schedule ran out, or the answers contradict each other: an operator decides. */
  record InquiryExhausted() implements SagaEvent {}

  record CommitAcknowledged() implements SagaEvent {}

  /** The core refused CommitHold (CommitFailed): the partner was paid but the hold is gone. */
  record CommitRefused() implements SagaEvent {}

  record ReleaseAcknowledged() implements SagaEvent {}

  /** The core refused ReleaseHold (ReleaseFailed): the hold was already committed. */
  record ReleaseRefused() implements SagaEvent {}

  record ReversalRequested() implements SagaEvent {}

  record ReversalAcknowledged() implements SagaEvent {}

  record ReversalFailed() implements SagaEvent {}

  /** Maker-checker decision on a MANUAL_REVIEW transaction. */
  record ManualCommit() implements SagaEvent {}

  record ManualRelease() implements SagaEvent {}

  /** The deadline stored in {@code saga_timer} passed; the meaning depends on the state. */
  record DeadlineExpired() implements SagaEvent {}
}
