package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.admin.api.client.AuditQueryClient;
import mfs.mw.admin.api.client.OrchestratorQueryClient;
import mfs.mw.admin.api.config.AdminApiProperties;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.AuditTrail;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.TxnDetailView;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import mfs.mw.admin.api.security.AdminRole;
import mfs.mw.admin.api.security.OperatorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Transaction search and detail for operators (ticket 36, spec 15.2, 15.4). Data comes from
 * mw-orchestrator's (search, timeline, partner calls) and mw-audit-dw's (audit trail) internal
 * query APIs, never from their schemas. Values are masked as the orchestrator masks them; only an
 * operator holding a {@link AdminRole#REVEAL_MSISDN} role who asks for it gets MSISDNs unmasked,
 * and that reveal is audited ({@link RevealAudit}) before the view is returned. Anyone else asking
 * gets the masked view and the orchestrator is not asked for unmasked values at all.
 */
@Service
public class TransactionQueryService {

  private static final Logger log = LoggerFactory.getLogger(TransactionQueryService.class);

  /** Search filters of {@code admin.v1}; all optional, ANDed. */
  public record Criteria(
      UUID txnId,
      String msisdn,
      String partnerRef,
      String status,
      String txnType,
      Instant from,
      Instant to,
      int limit,
      int offset) {}

  private final OrchestratorQueryClient orchestrator;
  private final AuditQueryClient audit;
  private final RevealAudit reveals;
  private final AdminApiProperties props;

  public TransactionQueryService(
      OrchestratorQueryClient orchestrator, AuditQueryClient audit, RevealAudit reveals, AdminApiProperties props) {
    this.orchestrator = orchestrator;
    this.audit = audit;
    this.reveals = reveals;
    this.props = props;
  }

  public AdminTxn.Page search(Operator operator, Criteria c) {
    requireReader(operator);
    if (c.from() != null && c.to() != null && !c.from().isBefore(c.to())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("from", "must be before to")));
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("txnId", c.txnId());
    filters.put("msisdn", c.msisdn());
    filters.put("partnerRef", c.partnerRef());
    filters.put("status", c.status());
    filters.put("txnType", c.txnType());
    filters.put("from", c.from());
    filters.put("to", c.to());
    filters.put("limit", c.limit());
    filters.put("offset", c.offset());
    try {
      return orchestrator.search(filters);
    } catch (QueryUnavailableException e) {
      throw unavailable(e);
    }
  }

  /**
   * @param reveal the operator asks for unmasked MSISDNs; granted only to a {@link
   *     AdminRole#REVEAL_MSISDN} role, audited
   */
  public TxnDetailView detail(Operator operator, UUID txnId, boolean reveal) {
    requireReader(operator);
    Optional<AdminRole> grantedBy = reveal ? operator.first(AdminRole.REVEAL_MSISDN) : Optional.empty();
    AdminTxn.Detail detail;
    AuditTrail.Page trail;
    try {
      detail =
          orchestrator
              .detail(txnId, grantedBy.isPresent())
              .orElseThrow(
                  () -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such transaction").withStatus(HttpStatus.NOT_FOUND));
      trail = audit.ofTransaction(txnId, props.auditTrailLimit());
    } catch (QueryUnavailableException e) {
      throw unavailable(e);
    }
    grantedBy.ifPresent(role -> reveals.record(operator, role, txnId));
    return new TxnDetailView(
        detail.transaction(), detail.timeline(), detail.partnerCalls(), trail.items(), grantedBy.isPresent());
  }

  private static void requireReader(Operator operator) {
    if (!operator.hasAny(AdminRole.READ_TRANSACTIONS)) {
      throw OperatorResolver.forbidden("This operation needs a transaction viewing role");
    }
  }

  private static MwException unavailable(QueryUnavailableException e) {
    log.atWarn()
        .addKeyValue("event", "admin.txn_query.unavailable")
        .addKeyValue("errorCode", ErrorCodes.SYS_QUERY_UNAVAILABLE)
        .log("transaction query unavailable: {}", e.getMessage());
    return new MwException(ErrorCodes.SYS_QUERY_UNAVAILABLE, "Transaction query temporarily unavailable", e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }
}
