package mfs.mw.portal.api.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.portal.api.client.OrchestratorQueryClient;
import mfs.mw.portal.api.config.PortalApiProperties;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.dto.PortalUser;
import mfs.mw.portal.api.exception.ErrorCodes;
import mfs.mw.portal.api.exception.MwException;
import mfs.mw.portal.api.exception.OrchestratorUnavailableException;
import mfs.mw.portal.api.mapper.SpReportFile;
import mfs.mw.portal.api.security.PortalUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The SP portal (ticket 129d, FRS UC-20, CH-POR-04, CH-POR-05): a service provider reads its own
 * transactions and a CSV report of them. The SP is the org of the token ({@link PortalUser#orgId()});
 * every read goes to mw-orchestrator's internal query API with that org and only the filters named
 * here, never a request parameter for the org. The orchestrator decides what belongs to the org
 * (initiated by it, or collected by it as payee). Role {@code sp-viewer} only.
 */
@Service
public class SpPortalService {

  private static final Logger log = LoggerFactory.getLogger(SpPortalService.class);
  /** The orchestrator's largest page. */
  static final int PAGE = 200;

  private final OrchestratorQueryClient orchestrator;
  private final PortalApiProperties props;

  public SpPortalService(OrchestratorQueryClient orchestrator, PortalApiProperties props) {
    this.orchestrator = orchestrator;
    this.props = props;
  }

  /** @param status a saga state, already checked by the controller */
  public OrgTxn.Page search(PortalUser user, String status, Instant from, Instant to, int limit, int offset) {
    requireViewer(user);
    if (from != null && to != null && !from.isBefore(to)) {
      throw invalid("from", "must be before to");
    }
    return query(user, filters(status, from, to, limit, offset));
  }

  public OrgTxn.Detail detail(PortalUser user, UUID txnId) {
    requireViewer(user);
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

  /** The org's transactions created in [from, to) as CSV ({@link SpReportFile}), at most the report cap. */
  public String report(PortalUser user, String status, Instant from, Instant to) {
    requireViewer(user);
    if (!from.isBefore(to)) {
      throw invalid("from", "must be before to");
    }
    Duration maxRange = props.sp().reportMaxRange();
    if (Duration.between(from, to).compareTo(maxRange) > 0) {
      throw invalid("to", "range longer than " + maxRange.toDays() + " days");
    }
    int maxRows = props.sp().reportMaxRows();
    List<OrgTxn> rows = new ArrayList<>();
    for (int offset = 0; ; offset += PAGE) {
      List<OrgTxn> page = query(user, filters(status, from, to, PAGE, offset)).items();
      rows.addAll(page);
      if (rows.size() > maxRows) {
        throw new MwException(ErrorCodes.VAL_REPORT_TOO_LARGE, "More than " + maxRows + " transactions: narrow the range")
            .withDetails(List.of(new ErrorDetail("from", "range holds more than " + maxRows + " transactions")));
      }
      if (page.size() < PAGE) {
        return SpReportFile.of(rows);
      }
    }
  }

  private OrgTxn.Page query(PortalUser user, Map<String, Object> filters) {
    try {
      return orchestrator.search(user.orgId(), filters);
    } catch (OrchestratorUnavailableException e) {
      throw unavailable(e);
    }
  }

  private static Map<String, Object> filters(String status, Instant from, Instant to, int limit, int offset) {
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("status", status);
    filters.put("from", from);
    filters.put("to", to);
    filters.put("limit", limit);
    filters.put("offset", offset);
    return filters;
  }

  private void requireViewer(PortalUser user) {
    if (!user.has(props.roles().spViewer())) {
      throw PortalUserResolver.forbidden("This operation needs the SP portal role");
    }
  }

  private static MwException invalid(String field, String issue) {
    return new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
        .withDetails(List.of(new ErrorDetail(field, issue)));
  }

  private static MwException unavailable(OrchestratorUnavailableException e) {
    log.atWarn()
        .addKeyValue("event", "portal.sp_query.unavailable")
        .addKeyValue("errorCode", ErrorCodes.SYS_ORCHESTRATOR_UNAVAILABLE)
        .log("SP query unavailable: {}", e.getMessage());
    return new MwException(ErrorCodes.SYS_ORCHESTRATOR_UNAVAILABLE, "Transaction query temporarily unavailable", e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }
}
