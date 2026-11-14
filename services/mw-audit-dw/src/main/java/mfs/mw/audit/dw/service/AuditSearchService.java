package mfs.mw.audit.dw.service;

import java.util.List;
import mfs.mw.audit.dw.dto.AnchorStatus;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.dto.ErrorDetail;
import mfs.mw.audit.dw.exception.ErrorCodes;
import mfs.mw.audit.dw.exception.MwException;
import mfs.mw.audit.dw.repository.AuditSearchRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The internal audit search (ticket 35, spec 14.3, 16.2) for mw-admin-api: by txnId, actor,
 * action, resource, outcome and time window, as the read-only reader. Stored values are already
 * masked, so they are returned as they are; operator usernames are not masked (staff, not
 * customers). Every page carries the anchor status of the last verification ({@code OFF} when
 * anchoring is switched off, ticket 74).
 */
@Service
public class AuditSearchService {

  private final AuditSearchRepository repository;
  private final ObjectProvider<AnchorService> anchors;

  public AuditSearchService(AuditSearchRepository repository, ObjectProvider<AnchorService> anchors) {
    this.repository = repository;
    this.anchors = anchors;
  }

  public AuditEntry.Page search(AuditSearchCriteria criteria) {
    if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("from", "must be before to")));
    }
    AnchorService anchor = anchors.getIfAvailable();
    return new AuditEntry.Page(
        repository.search(criteria),
        criteria.limit(),
        criteria.offset(),
        anchor == null ? AnchorStatus.off() : anchor.status());
  }
}
