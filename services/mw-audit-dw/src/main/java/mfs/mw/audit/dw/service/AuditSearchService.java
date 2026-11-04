package mfs.mw.audit.dw.service;

import java.util.List;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.dto.ErrorDetail;
import mfs.mw.audit.dw.exception.ErrorCodes;
import mfs.mw.audit.dw.exception.MwException;
import mfs.mw.audit.dw.repository.AuditSearchRepository;
import org.springframework.stereotype.Service;

/**
 * The internal audit search (ticket 35, spec 14.3, 16.2) for mw-admin-api: by txnId, actor,
 * action, resource, outcome and time window, as the read-only reader. Stored values are already
 * masked, so they are returned as they are; operator usernames are not masked (staff, not
 * customers).
 */
@Service
public class AuditSearchService {

  private final AuditSearchRepository repository;

  public AuditSearchService(AuditSearchRepository repository) {
    this.repository = repository;
  }

  public AuditEntry.Page search(AuditSearchCriteria criteria) {
    if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("from", "must be before to")));
    }
    return new AuditEntry.Page(repository.search(criteria), criteria.limit(), criteria.offset());
  }
}
