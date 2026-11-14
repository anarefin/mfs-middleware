package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import mfs.mw.admin.api.util.Sensitive;
import mfs.mw.admin.api.util.SensitiveType;

/**
 * This service's own type for {@code auth.wallet-lock.v1} (contract owned by mw-auth, ticket 70): the
 * approved ops unlock, so mw-auth lifts its PIN lock (every level) and any ADMIN lock ({@code
 * <changeRequestId>:auth-unlock}), and the approved care PIN reset ({@code
 * <changeRequestId>:pin-reset}, with makerId). Key the wallet id; mw-auth applies each once.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletLockCommand(
    int schemaVersion,
    String commandId,
    String action,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String reason,
    Actor actor,
    String changeRequestId,
    String makerId,
    String correlationId,
    Instant issuedAt) {

  public static final String UNLOCK = "UNLOCK";
  /** An approved care PIN reset: mw-auth opens it from this committed command (security review). */
  public static final String PIN_RESET = "PIN_RESET";

  /** Who decided it: the checker ({@code user}). */
  public record Actor(String type, String id) {}
}
