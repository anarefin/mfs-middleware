package mfs.mw.orchestrator.entity;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.orchestrator.dto.EncryptedPin;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.saga.Status;

/**
 * One row of {@code txn.transaction}: the request as accepted plus the saga state. Never leaves
 * the service layer (spec 4.4). The PIN ciphertext is kept only until the transaction is terminal.
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
    EncryptedPin encryptedPin) {

  public TransactionRecord moved(Status to, Instant at, ErrorInfo newError) {
    return new TransactionRecord(
        txnId, createdAt, at, request, to, status, replyTo, feeMinor, coreRef,
        newError != null ? newError : error, to.terminal() ? null : encryptedPin);
  }

  public TransactionRecord withCore(String newCoreRef, Long newFeeMinor) {
    return new TransactionRecord(
        txnId, createdAt, updatedAt, request, status, previousStatus, replyTo,
        newFeeMinor != null ? newFeeMinor : feeMinor,
        newCoreRef != null ? newCoreRef : coreRef, error, encryptedPin);
  }
}
