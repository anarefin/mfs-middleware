package mfs.mw.admin.api.dto;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The operator roles of spec 15.4, as realm roles of Keycloak realm {@code ops}. A realm role with
 * any other name grants nothing here. What each role may do is said once, in the sets below
 * (ticket 36); later tickets add their write permissions next to them.
 */
public enum AdminRole {
  VIEWER("viewer"),
  OPS_MAKER("ops-maker"),
  OPS_CHECKER("ops-checker"),
  OPS_EMERGENCY("ops-emergency"),
  INTEGRATION_MAKER("integration-maker"),
  INTEGRATION_CHECKER("integration-checker"),
  RECON_MAKER("recon-maker"),
  RECON_CHECKER("recon-checker"),
  COMPLIANCE_OFFICER("compliance-officer"),
  SECURITY_ADMIN("security-admin"),
  AUDITOR("auditor");

  /** Search transactions and open one with its timeline and audit trail (spec 15.2 Transactions). */
  public static final Set<AdminRole> READ_TRANSACTIONS =
      Set.copyOf(
          EnumSet.of(
              VIEWER, OPS_MAKER, OPS_CHECKER, OPS_EMERGENCY, RECON_MAKER, RECON_CHECKER, COMPLIANCE_OFFICER, AUDITOR));

  /** See an unmasked MSISDN; every reveal is audited (spec 15.4). In order of preference for the audit. */
  public static final Set<AdminRole> REVEAL_MSISDN = Set.copyOf(EnumSet.of(COMPLIANCE_OFFICER, AUDITOR));

  /**
   * Ask for an operator action on a transaction: manual TSQ, force commit / release, reversal
   * (spec 15.2 Transactions, maker-checker; ticket 37).
   */
  public static final Set<AdminRole> TXN_MAKER = Set.copyOf(EnumSet.of(OPS_MAKER));

  /** Approve or reject such a request; never the maker's own (spec 15.3). */
  public static final Set<AdminRole> TXN_CHECKER = Set.copyOf(EnumSet.of(OPS_CHECKER));

  /**
   * Ask for a partner config change: enable / disable, timeouts, onboard or replace the config
   * (spec 15.2 Partners, maker-checker; ticket 38). Operations staff disable a partner during an
   * incident; integration staff onboard one.
   */
  public static final Set<AdminRole> PARTNER_MAKER = Set.copyOf(EnumSet.of(OPS_MAKER, INTEGRATION_MAKER));

  /** Approve or reject a partner change request; never the maker's own (spec 15.3). */
  public static final Set<AdminRole> PARTNER_CHECKER = Set.copyOf(EnumSet.of(OPS_CHECKER, INTEGRATION_CHECKER));

  /** See partner configs (no secrets in them): the transaction readers and integration staff. */
  public static final Set<AdminRole> READ_PARTNERS =
      Set.copyOf(
          EnumSet.of(
              VIEWER, OPS_MAKER, OPS_CHECKER, OPS_EMERGENCY, INTEGRATION_MAKER, INTEGRATION_CHECKER, RECON_MAKER,
              RECON_CHECKER, COMPLIANCE_OFFICER, AUDITOR));

  /** See change requests: whoever may read transactions or partners. */
  public static final Set<AdminRole> READ_CHANGE_REQUESTS = READ_PARTNERS;

  /** Decide some change request (the service checks the resource type's own checker set). */
  public static final Set<AdminRole> ANY_CHECKER = Set.copyOf(EnumSet.of(OPS_CHECKER, INTEGRATION_CHECKER));

  /**
   * Propose a new version of a USSD menu or a notify template (spec 15.2 USSD, Notifications;
   * customer-facing text, maker-checker; ticket 39). Decision: the ops roles, as for transactions.
   */
  public static final Set<AdminRole> CONTENT_MAKER = Set.copyOf(EnumSet.of(OPS_MAKER));

  /** Approve or reject such a version; never the maker's own (spec 15.3). */
  public static final Set<AdminRole> CONTENT_CHECKER = Set.copyOf(EnumSet.of(OPS_CHECKER));

  /** Never change anything: no method but GET/HEAD/OPTIONS (spec 15.4: viewer; auditor read-only). */
  public static final Set<AdminRole> READ_ONLY = Set.copyOf(EnumSet.of(VIEWER, AUDITOR));

  private static final Map<String, AdminRole> BY_ID =
      Stream.of(values()).collect(Collectors.toUnmodifiableMap(AdminRole::id, Function.identity()));

  private final String id;

  AdminRole(String id) {
    this.id = id;
  }

  /** The realm role name, e.g. {@code compliance-officer}. */
  public String id() {
    return id;
  }

  public static Optional<AdminRole> of(String id) {
    return Optional.ofNullable(id == null ? null : BY_ID.get(id));
  }

  /** The spec 15.4 roles among the token's realm roles; unknown names are dropped. */
  public static Set<AdminRole> from(Collection<String> realmRoles) {
    EnumSet<AdminRole> out = EnumSet.noneOf(AdminRole.class);
    if (realmRoles != null) {
      realmRoles.forEach(r -> of(r).ifPresent(out::add));
    }
    return Set.copyOf(out);
  }

  /** Whether an operator with {@code roles} may use a method that changes something. */
  public static boolean mayWrite(Set<AdminRole> roles) {
    return roles.stream().anyMatch(r -> !READ_ONLY.contains(r));
  }
}
