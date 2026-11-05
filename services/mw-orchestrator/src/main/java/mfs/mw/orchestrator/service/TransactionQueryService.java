package mfs.mw.orchestrator.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.config.SearchProperties;
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
import mfs.mw.orchestrator.util.SearchHash;
import mfs.mw.orchestrator.util.SearchKeys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The internal query API (ticket 35, spec 15.2, 16.2): transaction search and the saga timeline
 * for mw-admin-api and mw-portal-api, which never read the {@code txn} schema. Read only; every
 * view is masked ({@link TransactionViewMapper}).
 *
 * <p>Org scope (A2/A3): with an {@code orgId}, a transaction belongs to the org when the org
 * initiated it (initiator {@code {ORG, orgId}}, type checked, never an MSISDN with the same text)
 * or when it pays one of the billers / merchant wallets the org collects for ({@code
 * mw.orchestrator.search.org-payees}); each is tagged {@code INITIATOR} or {@code PAYEE}.
 */
@Service
public class TransactionQueryService {

  public static final String INITIATOR = "INITIATOR";
  public static final String PAYEE = "PAYEE";

  private final TransactionRepository transactions;
  private final PartnerCallRepository partnerCalls;
  private final SearchProperties search;
  private final SearchHash hash;

  public TransactionQueryService(
      TransactionRepository transactions, PartnerCallRepository partnerCalls, SearchProperties search, SearchHash hash) {
    this.transactions = transactions;
    this.partnerCalls = partnerCalls;
    this.search = search;
    this.hash = hash;
  }

  @Transactional(readOnly = true)
  public TxnPage search(TxnSearchCriteria criteria) {
    if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("from", "must be before to")));
    }
    String orgId = criteria.orgId();
    List<SearchKeys.Key> payees = orgId == null ? List.of() : search.payeesOf(orgId);
    List<TransactionRecord> found = transactions.search(criteria, payees);
    return new TxnPage(
        found.stream()
            .map(r -> TransactionViewMapper.toView(r, orgId == null ? null : role(r, orgId).orElse(PAYEE)))
            .toList(),
        criteria.limit(),
        criteria.offset());
  }

  /**
   * One transaction with its timeline. With {@code orgId}, only a transaction of that org (as
   * initiator or payee) is found: any other is 404, exactly like a txnId that does not exist.
   */
  @Transactional(readOnly = true)
  public TxnDetail detail(UUID txnId, String orgId) {
    return detail(txnId, orgId, false);
  }

  /**
   * @param reveal party MSISDNs unmasked (ticket 36; only a reveal client gets here, see {@code
   *     InternalCallerPolicy}, and mw-admin-api audits the operator's reveal)
   */
  @Transactional(readOnly = true)
  public TxnDetail detail(UUID txnId, String orgId, boolean reveal) {
    TransactionRecord rec = transactions.find(txnId).orElseThrow(TransactionQueryService::notFound);
    String role = null;
    if (orgId != null) {
      role = role(rec, orgId).orElseThrow(TransactionQueryService::notFound);
    }
    return new TxnDetail(
        TransactionViewMapper.toView(rec, role, reveal), transactions.timeline(txnId), partnerCalls.of(txnId));
  }

  /** The org's role in {@code rec}: INITIATOR before PAYEE; empty when it is neither. */
  private Optional<String> role(TransactionRecord rec, String orgId) {
    var initiator = rec.request().initiator();
    if (initiator != null && SearchKeys.ORG.equals(initiator.type()) && orgId.equals(initiator.id())) {
      return Optional.of(INITIATOR);
    }
    Optional<byte[]> payee = SearchKeys.payee(rec.request()).map(hash::of);
    if (payee.isPresent() && search.payeesOf(orgId).stream().anyMatch(p -> Arrays.equals(hash.of(p), payee.get()))) {
      return Optional.of(PAYEE);
    }
    return Optional.empty();
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Transaction not found").withStatus(HttpStatus.NOT_FOUND);
  }
}
