package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.dto.AdminRole;

/**
 * What approving a change request on one resource type does (ticket 38; ticket 39 adds USSD menus
 * and SMS templates). {@link ChangeRequestService} keeps the shared rules (maker != checker,
 * expiry, the PENDING guard, the audit) and runs {@link Approval#apply()} in the same database
 * transaction as the status change and its audit. Transactions (ticket 37) are handled by {@link
 * ChangeRequestService} itself.
 */
public interface ResourceChangeHandler {

  /** {@code ChangeRequest.resourceType} this handler owns, e.g. {@code partner}. */
  String resourceType();

  /** Roles that may approve or reject such a request (never the maker's own). */
  Set<AdminRole> checkers();

  /**
   * The work an approval does; throw an {@code MwException} when the request can no longer be
   * applied (the request then stays PENDING). Called outside the transaction; {@code apply} runs
   * inside it and may throw too (everything rolls back).
   */
  Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId);

  /**
   * Called inside the decision transaction when a request of this type is rejected or expires
   * (ticket 39: the PENDING content version is marked so). Nothing by default.
   *
   * @param checkerId the checker who rejected it; null on expiry
   */
  default void closed(ChangeRequest cr, ChangeRequest.Status status, String checkerId, Instant now) {}

  /**
   * @param payload the request's payload as stored after the approval
   * @param sentAs the topic the change went out on (audited)
   * @param apply the change itself, run inside the decision transaction; returns entries added to
   *     the stored payload
   */
  record Approval(Map<String, Object> payload, String sentAs, java.util.function.Supplier<Map<String, Object>> apply) {}
}
