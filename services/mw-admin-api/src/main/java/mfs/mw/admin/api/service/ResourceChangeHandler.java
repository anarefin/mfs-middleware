package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
   * Extra rules on the operator deciding (approve or reject) a request of this type, after the
   * shared ones (ticket 75: a fresh MFA for keys); throw an {@code MwException} to refuse. Nothing
   * by default.
   */
  default void checkDecider(ChangeRequest cr, Operator checker, Instant now) {}

  /**
   * Whether the approval's effect is a synchronous call to another service (ticket 75: mw-crypto),
   * approved claim-first: the request is moved PENDING -> APPROVING before {@link #prepare} (which
   * then makes the call, idempotent on the change request id), and APPROVING -> APPROVED after it;
   * a definite refusal (4xx) puts it back to PENDING, a failure without an answer (timeout, 5xx)
   * leaves it APPROVING for a retry of the approval, which calls again with the same id. Reject and
   * expiry refuse an APPROVING request. False by default (the effect goes through the outbox in the
   * decision transaction).
   */
  default boolean claimFirst() {
    return false;
  }

  /**
   * Claim-first types: checks that need no remote call, before the claim (ticket 75: a ceremony
   * needs both components); throw an {@code MwException} to refuse (the request stays PENDING).
   */
  default void precheck(ChangeRequest cr) {}

  /**
   * Claim-first types: {@link #prepare} for approval attempt {@code attemptId} (the claim token,
   * the same on every retry of one claim), the id the other service applies the change under.
   */
  default Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId, UUID attemptId) {
    return prepare(cr, checker, now, correlationId);
  }

  /**
   * Claim-first types: settles attempt {@code attemptId} at the other service before the claim is
   * given up, making "not applied" binding (security review): present with the payload entries
   * when it was applied, empty when it is now cancelled for good (a late call of that attempt is
   * refused there); throw an {@code MwException} when it cannot tell (the claim stays APPROVING).
   */
  default Optional<Map<String, Object>> settle(ChangeRequest cr, UUID attemptId) {
    throw new UnsupportedOperationException(resourceType() + " is not approved claim-first");
  }

  /**
   * Whether a request whose attempt was cancelled can be approved again (back to PENDING, a new
   * attempt id); false makes it EXPIRED (a key ceremony: its cancel aborts the ceremony for good).
   */
  default boolean reusableAfterCancel(ChangeRequest cr) {
    return true;
  }

  /**
   * Reconciler hook for state the other service keeps on its own (ticket 75: ceremonies left open
   * for requests that were rejected, expired or never filed); {@code find} reads a request,
   * {@code olderThan} spares what may still be in flight. Nothing by default.
   */
  default void reconcile(java.util.function.Function<UUID, Optional<ChangeRequest>> find, Instant olderThan) {}

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
