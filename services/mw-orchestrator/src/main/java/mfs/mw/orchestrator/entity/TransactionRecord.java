package mfs.mw.orchestrator.entity;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.orchestrator.dto.EncryptedPin;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.saga.Flow;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.service.saga.Status;

/**
 * One row of {@code txn.transaction}: the request as accepted plus the saga state. Never leaves
 * the service layer (spec 4.4). The PIN ciphertext is kept only until the transaction is terminal.
 * {@code holdId} is the core's hold (flows that hold), {@code partnerRef} the partner's own
 * reference after a successful partner call, {@code reversalTxnId} the txnId of the REVERSAL
 * request that compensates this transaction (set when it moves to REVERSING).
 */
public record TransactionRecord(
    UUID txnId,
    Instant createdAt,
    Instant updatedAt,
    TxnRequest request,
    Status status,
    Status previousStatus,
    String replyTo,
    Long feeMinor,
    String coreRef,
    ErrorInfo error,
    EncryptedPin encryptedPin,
    String holdId,
    String partnerRef,
    UUID reversalTxnId) {

  public TransactionRecord moved(Status to, Instant at, ErrorInfo newError) {
    return new TransactionRecord(
        txnId, createdAt, at, request, to, status, replyTo, feeMinor, coreRef,
        newError != null ? newError : error, to.terminal() ? null : encryptedPin, holdId, partnerRef,
        reversalTxnId);
  }

  public TransactionRecord withCore(String newCoreRef, Long newFeeMinor) {
    return new TransactionRecord(
        txnId, createdAt, updatedAt, request, status, previousStatus, replyTo,
        newFeeMinor != null ? newFeeMinor : feeMinor,
        newCoreRef != null ? newCoreRef : coreRef, error, encryptedPin, holdId, partnerRef,
        reversalTxnId);
  }

  /** The core's hold id, when an event carried one (kept otherwise). */
  public TransactionRecord withHold(String newHoldId) {
    return newHoldId == null
        ? this
        : new TransactionRecord(
            txnId, createdAt, updatedAt, request, status, previousStatus, replyTo, feeMinor,
            coreRef, error, encryptedPin, newHoldId, partnerRef, reversalTxnId);
  }

  /** The partner's reference, when a reply carried one (kept otherwise). */
  public TransactionRecord withPartnerRef(String newPartnerRef) {
    return newPartnerRef == null
        ? this
        : new TransactionRecord(
            txnId, createdAt, updatedAt, request, status, previousStatus, replyTo, feeMinor,
            coreRef, error, encryptedPin, holdId, newPartnerRef, reversalTxnId);
  }

  /**
   * Whether this transaction holds funds a refusal must release: its flow holds (TOPUP,
   * BILL_PAY), or, for a txnType no flow runs, whether the core reported a hold.
   */
  public boolean holdsFunds(FlowRegistry flows) {
    return flows.find(request.txnType()).map(Flow::needsHold).orElse(holdId != null);
  }

  /** The REVERSAL request that compensates this transaction. */
  public TransactionRecord withReversal(UUID newReversalTxnId) {
    return new TransactionRecord(
        txnId, createdAt, updatedAt, request, status, previousStatus, replyTo, feeMinor, coreRef,
        error, encryptedPin, holdId, partnerRef, newReversalTxnId);
  }
}
