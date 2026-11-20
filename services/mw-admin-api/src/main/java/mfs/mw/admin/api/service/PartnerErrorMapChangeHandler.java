package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.Set;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import org.springframework.stereotype.Component;

/**
 * Approving a {@code partner_error_map} change request (ticket 131, FRS ADM-08): the payload has
 * the partner request's shape (family, baseVersion, the full config carrying the new error map),
 * so the approval is exactly {@link PartnerChangeHandler}'s: the partner row still at {@code
 * baseVersion} (else 409, stays PENDING), version + 1, the full config on {@code
 * mw.config.changed.v1} in the decision transaction. Its own resource type keeps the screen's
 * requests apart (one open per partner and type) with the partner checkers.
 */
@Component
public class PartnerErrorMapChangeHandler implements ResourceChangeHandler {

  private final PartnerChangeHandler partner;

  public PartnerErrorMapChangeHandler(PartnerChangeHandler partner) {
    this.partner = partner;
  }

  @Override
  public String resourceType() {
    return PartnerConfigService.ERROR_MAP_RESOURCE_TYPE;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.PARTNER_CHECKER;
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    return partner.prepare(cr, checker, now, correlationId);
  }
}
