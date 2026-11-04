package mfs.mw.orchestrator.service.saga;

/** Inputs of the state machine; payload data stays with the service, the machine only needs the facts. */
public sealed interface SagaEvent {

  record ValidationStarted() implements SagaEvent {}

  record RequestRejected() implements SagaEvent {}

  /** The core accepted ValidateTransaction; {@code needsHold} comes from the flow of the txn type. */
  record Validated(boolean needsHold) implements SagaEvent {}

  record ValidationRejected() implements SagaEvent {}

  record HoldPlaced() implements SagaEvent {}

  record HoldRejected() implements SagaEvent {}

  /** Answer to the QueryTransaction sent after a HOLDING timeout. */
  record HoldQueryResult(boolean held) implements SagaEvent {}

  record PostSucceeded() implements SagaEvent {}

  record PostRejected() implements SagaEvent {}

  record PartnerSucceeded() implements SagaEvent {}

  record PartnerFailed() implements SagaEvent {}

  record PartnerUnknown() implements SagaEvent {}

  record InquirySucceeded() implements SagaEvent {}

  record InquiryFailed() implements SagaEvent {}

  record InquiryExhausted() implements SagaEvent {}

  record CommitAcknowledged() implements SagaEvent {}

  record ReleaseAcknowledged() implements SagaEvent {}

  record ReversalRequested() implements SagaEvent {}

  record ReversalAcknowledged() implements SagaEvent {}

  record ReversalFailed() implements SagaEvent {}

  /** Maker-checker decision on a MANUAL_REVIEW transaction. */
  record ManualCommit() implements SagaEvent {}

  record ManualRelease() implements SagaEvent {}

  /** The deadline stored in {@code saga_timer} passed; the meaning depends on the state. */
  record DeadlineExpired() implements SagaEvent {}
}
