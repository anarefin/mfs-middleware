package mfs.mw.audit.dw.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AnchorStatus;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.dto.ErrorDetail;
import mfs.mw.audit.dw.dto.TimelineHop;
import mfs.mw.audit.dw.exception.ErrorCodes;
import mfs.mw.audit.dw.exception.MwException;
import mfs.mw.audit.dw.repository.AuditSearchRepository;
import mfs.mw.audit.dw.util.Msisdns;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The internal audit search (ticket 35, spec 14.3, 16.2) for mw-admin-api: by txnId, actor,
 * action, resource, outcome and time window, and (ticket 130, FRS AUD-03) by the transaction's
 * partner, channel, status, amount range and a party's MSISDN, as the read-only reader. An MSISDN is
 * tokenised ({@code TOKENISE_MSISDN}) and only its token is searched. Stored values are already
 * masked, so they are returned as they are; operator usernames are not masked (staff, not
 * customers). Every page carries the anchor status of the last verification ({@code OFF} when
 * anchoring is switched off, ticket 74). Also one transaction's per-hop timeline (FRS AUD-04).
 */
@Service
public class AuditSearchService {

  private final AuditSearchRepository repository;
  private final ObjectProvider<AnchorService> anchors;
  private final TxnIndexService txnIndex;

  public AuditSearchService(
      AuditSearchRepository repository, ObjectProvider<AnchorService> anchors, TxnIndexService txnIndex) {
    this.repository = repository;
    this.anchors = anchors;
    this.txnIndex = txnIndex;
  }

  public AuditEntry.Page search(AuditSearchCriteria criteria) {
    return search(criteria, null);
  }

  /** @param msisdn a party's MSISDN as the operator typed it, or null */
  public AuditEntry.Page search(AuditSearchCriteria criteria, String msisdn) {
    AuditSearchCriteria resolved = resolve(criteria, msisdn);
    return new AuditEntry.Page(
        repository.search(resolved), resolved.limit(), resolved.offset(), anchorStatus());
  }

  /** The checked criteria with the MSISDN replaced by its token. */
  public AuditSearchCriteria resolve(AuditSearchCriteria criteria, String msisdn) {
    if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
      throw invalid("from", "must be before to");
    }
    if (criteria.amountMin() != null && criteria.amountMax() != null && criteria.amountMin() > criteria.amountMax()) {
      throw invalid("amountMin", "must not be above amountMax");
    }
    if (msisdn == null) {
      return criteria;
    }
    String normal = Msisdns.normalise(msisdn).orElseThrow(() -> invalid("msisdn", "must be a mobile number"));
    String token =
        txnIndex
            .tokenFor(normal)
            .orElseThrow(() -> new MwException(ErrorCodes.SYS_UNAVAILABLE, "MSISDN search is not available"));
    return criteria.withMsisdnToken(token);
  }

  /**
   * Every hop of one transaction in the order it happened, with its latency, the time since the
   * first hop and since the previous one, and its masked payloads.
   */
  public TimelineHop.Timeline timeline(UUID txnId) {
    List<TimelineHop> raw = repository.timeline(txnId);
    List<TimelineHop> hops = new ArrayList<>();
    TimelineHop previous = null;
    for (TimelineHop h : raw) {
      long sinceStart = raw.isEmpty() ? 0 : Duration.between(raw.get(0).at(), h.at()).toMillis();
      Long gap = previous == null ? null : Duration.between(previous.at(), h.at()).toMillis();
      hops.add(h.withLatencies(sinceStart, gap));
      previous = h;
    }
    long total = raw.isEmpty() ? 0 : Duration.between(raw.get(0).at(), raw.get(raw.size() - 1).at()).toMillis();
    return new TimelineHop.Timeline(txnId.toString(), hops, total, anchorStatus());
  }

  private AnchorStatus anchorStatus() {
    AnchorService anchor = anchors.getIfAvailable();
    return anchor == null ? AnchorStatus.off() : anchor.status();
  }

  private static MwException invalid(String field, String issue) {
    return new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
        .withDetails(List.of(new ErrorDetail(field, issue)));
  }
}
