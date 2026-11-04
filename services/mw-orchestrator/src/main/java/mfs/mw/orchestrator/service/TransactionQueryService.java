package mfs.mw.orchestrator.service;

import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.dto.ErrorDetail;
import mfs.mw.orchestrator.dto.TxnDetail;
import mfs.mw.orchestrator.dto.TxnPage;
import mfs.mw.orchestrator.dto.TxnSearchCriteria;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.mapper.TransactionViewMapper;
import mfs.mw.orchestrator.repository.PartnerCallRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The internal query API (ticket 35, spec 15.2, 16.2): transaction search and the saga timeline
 * for mw-admin-api and mw-portal-api, which never read the {@code txn} schema. Read only; every
 * view is masked ({@link TransactionViewMapper}).
 */
@Service
public class TransactionQueryService {

  /** The initiator type of an org's transactions (bulk children, ticket 34). */
  static final String ORG = "ORG";

  private final TransactionRepository transactions;
  private final PartnerCallRepository partnerCalls;

  public TransactionQueryService(TransactionRepository transactions, PartnerCallRepository partnerCalls) {
    this.transactions = transactions;
    this.partnerCalls = partnerCalls;
  }

  @Transactional(readOnly = true)
  public TxnPage search(TxnSearchCriteria criteria) {
    if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("from", "must be before to")));
    }
    List<TransactionRecord> found = transactions.search(criteria);
    return new TxnPage(
        found.stream().map(TransactionViewMapper::toView).toList(), criteria.limit(), criteria.offset());
  }

  /**
   * One transaction with its timeline. With {@code orgId}, only a transaction that org initiated
   * is found: any other is 404, exactly like a txnId that does not exist.
   */
  @Transactional(readOnly = true)
  public TxnDetail detail(UUID txnId, String orgId) {
    TransactionRecord rec =
        transactions.find(txnId).filter(r -> orgId == null || initiatedBy(r, orgId)).orElseThrow(TransactionQueryService::notFound);
    return new TxnDetail(TransactionViewMapper.toView(rec), transactions.steps(txnId), partnerCalls.of(txnId));
  }

  private static boolean initiatedBy(TransactionRecord rec, String orgId) {
    var initiator = rec.request().initiator();
    return initiator != null && ORG.equals(initiator.type()) && orgId.equals(initiator.id());
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Transaction not found").withStatus(HttpStatus.NOT_FOUND);
  }
}
