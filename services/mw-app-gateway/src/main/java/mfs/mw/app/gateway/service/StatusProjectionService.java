package mfs.mw.app.gateway.service;

import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import org.springframework.stereotype.Service;

/** Keeps {@code app_transaction} at the latest status record of each app transaction. */
@Service
public class StatusProjectionService {

  private final AppTransactionRepository transactions;

  public StatusProjectionService(AppTransactionRepository transactions) {
    this.transactions = transactions;
  }

  /**
   * Applies one status record. The answer to a repeated idempotency key is skipped: it repeats
   * the stored transaction's state, which its own records already carry. A record without
   * initiator or update time cannot be owned or ordered and is rejected.
   */
  public void apply(StatusMessage status) {
    if (status.duplicate()) {
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
}
