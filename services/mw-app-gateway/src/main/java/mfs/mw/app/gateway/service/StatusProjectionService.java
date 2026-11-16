package mfs.mw.app.gateway.service;

import java.util.UUID;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import org.springframework.stereotype.Service;

/**
 * Keeps {@code app_transaction} at the latest status record of each app transaction; a balance
 * check ({@code BALANCE}, ADR D26) is not a transaction of the history and is skipped.
 */
@Service
public class StatusProjectionService {

  private final AppTransactionRepository transactions;

  public StatusProjectionService(AppTransactionRepository transactions) {
    this.transactions = transactions;
  }

  /**
   * Applies one status record. A record without initiator or update time cannot be owned or
   * ordered and is rejected. The answer to a repeated idempotency key repeats the stored
   * transaction's state, which its own records already carry; it only links the txnId minted for
   * the repeat ({@code attributes.requestTxnId}) to the stored one, so GET on the id a 202 gave
   * for the repeat shows the stored transaction instead of a row that stays RECEIVED (S3).
   */
  public void apply(StatusMessage status) {
    if (RequestMapper.BALANCE.equals(status.txnType())) {
      // ADR D26: a balance check is never stored, so no app view ever serves one (or its balance)
      return;
    }
    if (status.duplicate()) {
      linkRepeat(status);
      return;
    }
    if (status.initiator() == null
        || status.initiator().id() == null
        || status.txnId() == null
        || status.updatedAt() == null) {
      throw new IllegalArgumentException("status record lacks txnId, initiator or updatedAt");
    }
    transactions.upsert(
        status.initiator().id(), status.channel(), TransactionView.of(status), status.updatedAt());
  }

  private void linkRepeat(StatusMessage status) {
    String requestTxnId = status.awaitedTxnId();
    if (status.initiator() == null
        || status.initiator().id() == null
        || status.txnId() == null
        || status.updatedAt() == null
        || requestTxnId == null
        || requestTxnId.equals(status.txnId())) {
      return;
    }
    transactions.linkRepeat(
        UUID.fromString(requestTxnId), UUID.fromString(status.txnId()), status.initiator().id(),
        status.channel(), TransactionView.of(status), status.updatedAt());
  }
}
