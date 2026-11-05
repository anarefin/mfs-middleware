package mfs.mw.portal.api.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.portal.api.client.OrchestratorQueryClient;
import mfs.mw.portal.api.config.PortalApiProperties;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.dto.PortalUser;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.exception.ErrorCodes;
import mfs.mw.portal.api.exception.MwException;
import mfs.mw.portal.api.exception.OrchestratorUnavailableException;
import mfs.mw.portal.api.repository.BatchRepository;
import mfs.mw.portal.api.security.PortalUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The SP transaction query (ticket 35, WP22): the SP is the org, and it sees the transactions it
 * initiated (its bulk children, role {@code INITIATOR}) and those it is the payee of (bill and
 * merchant payments it collects, role {@code PAYEE}; the orchestrator decides which, from its own
 * org payee config). The org is always the token's {@code orgId},
 * never a request parameter; the data comes from mw-orchestrator's internal query API, never
 * from its schema. Maker or checker may query.
 */
@Service
public class OrgTransactionService {

  private static final Logger log = LoggerFactory.getLogger(OrgTransactionService.class);

  private final OrchestratorQueryClient orchestrator;
  private final BatchRepository batches;
  private final PortalApiProperties props;

  public OrgTransactionService(
      OrchestratorQueryClient orchestrator, BatchRepository batches, PortalApiProperties props) {
    this.orchestrator = orchestrator;
    this.batches = batches;
    this.props = props;
  }

  /**
   * @param batchId only that batch's children (404 when the org has no such batch)
   * @param status a saga state, already checked by the controller
   */
  public OrgTxn.Page search(
      PortalUser user, String batchId, String status, Instant from, Instant to, int limit, int offset) {
    requireAny(user);
    if (from != null && to != null && !from.isBefore(to)) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("from", "must be before to")));
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    if (batchId != null) {
      Batch batch = batch(user, batchId);
      if (batch.parentTxnId() == null) { // never approved: nothing was sent
        return new OrgTxn.Page(List.of(), limit, offset);
      }
      filters.put("parentTxnId", batch.parentTxnId());
    }
    filters.put("status", status);
    filters.put("from", from);
    filters.put("to", to);
    filters.put("limit", limit);
    filters.put("offset", offset);
    try {
      return orchestrator.search(user.orgId(), filters);
    } catch (OrchestratorUnavailableException e) {
      throw unavailable(e);
    }
  }

  public OrgTxn.Detail detail(PortalUser user, UUID txnId) {
    requireAny(user);
    try {
      return orchestrator
          .detail(user.orgId(), txnId)
          .map(OrgTxn.Detail::statesOnly)
          .orElseThrow(
              () -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such transaction").withStatus(HttpStatus.NOT_FOUND));
    } catch (OrchestratorUnavailableException e) {
      throw unavailable(e);
    }
  }

  private Batch batch(PortalUser user, String batchId) {
    UUID id;
    try {
      id = UUID.fromString(batchId);
    } catch (IllegalArgumentException e) {
      throw noSuchBatch();
    }
    return batches.find(id, user.orgId()).orElseThrow(OrgTransactionService::noSuchBatch);
  }

  private void requireAny(PortalUser user) {
    if (!user.has(props.roles().maker()) && !user.has(props.roles().checker())) {
      throw PortalUserResolver.forbidden("This operation needs an org portal role");
    }
  }

  private static MwException noSuchBatch() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No such batch").withStatus(HttpStatus.NOT_FOUND);
  }

  private static MwException unavailable(OrchestratorUnavailableException e) {
    log.atWarn()
        .addKeyValue("event", "portal.txn_query.unavailable")
        .addKeyValue("errorCode", ErrorCodes.SYS_ORCHESTRATOR_UNAVAILABLE)
        .log("transaction query unavailable: {}", e.getMessage());
    return new MwException(ErrorCodes.SYS_ORCHESTRATOR_UNAVAILABLE, "Transaction query temporarily unavailable", e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }
}
