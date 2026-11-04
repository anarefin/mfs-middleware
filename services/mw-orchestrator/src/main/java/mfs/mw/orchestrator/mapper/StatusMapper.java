package mfs.mw.orchestrator.mapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.StatusMessage;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.Status;
import org.springframework.stereotype.Component;

/** Transaction row (or a refused request) to {@code mw.txn.status.v1}. */
@Component
public class StatusMapper {

  public static final String REVERSAL_TXN_ID = "reversalTxnId";

  public StatusMessage toStatus(TransactionRecord rec) {
    return build(rec, rec.request().attributes());
  }

  /**
   * The answer to a repeated request with the same idempotency key and content (7.3): the stored
   * transaction's current status under the stored txnId, with {@code attributes.requestTxnId}
   * naming the txnId of the request being answered so its sender can match it.
   */
  public StatusMessage toDuplicateStatus(TransactionRecord stored, String requestTxnId) {
    Map<String, String> attributes = new HashMap<>();
    if (stored.request().attributes() != null) {
      attributes.putAll(stored.request().attributes());
    }
    attributes.put("requestTxnId", requestTxnId);
    attributes.put("duplicate", "true");
    return build(stored, attributes);
  }

  /** REJECTED for a request that never became a transaction (key reused with other content). */
  public StatusMessage toRefusedStatus(TxnRequest req, ErrorInfo error, Instant now) {
    return new StatusMessage(
        1, req.txnId(), req.idempotencyKey(), req.correlationId(), req.channel(), req.txnType(),
        Status.REJECTED.name(), null, req.initiator(), req.debitParty(), req.creditParty(),
        req.amount(), null, req.partner(), null, null, req.originalTxnId(), error, req.locale(),
        req.attributes(), req.requestedAt(), now);
  }

  /**
   * A transaction being (or having been) reversed names the REVERSAL request in
   * {@code attributes.reversalTxnId}, so whoever asked for the reversal can follow it.
   */
  private StatusMessage build(TransactionRecord rec, Map<String, String> requestAttributes) {
    TxnRequest req = rec.request();
    Map<String, String> attributes = requestAttributes;
    if (rec.reversalTxnId() != null) {
      attributes = new HashMap<>();
      if (requestAttributes != null) {
        attributes.putAll(requestAttributes);
      }
      attributes.put(REVERSAL_TXN_ID, rec.reversalTxnId().toString());
    }
    return new StatusMessage(
        1, rec.txnId().toString(), req.idempotencyKey(), req.correlationId(), req.channel(),
        req.txnType(), rec.status().name(),
        rec.previousStatus() == null ? null : rec.previousStatus().name(), req.initiator(),
        req.debitParty(), req.creditParty(), req.amount(),
        rec.feeMinor() == null ? null : Amount.bdt(rec.feeMinor()), req.partner(), rec.partnerRef(),
        rec.coreRef(),
        req.originalTxnId(), rec.error(), req.locale(), attributes, req.requestedAt(),
        rec.updatedAt());
  }
}
