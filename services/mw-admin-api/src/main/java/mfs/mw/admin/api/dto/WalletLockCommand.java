package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import mfs.mw.admin.api.util.Sensitive;
import mfs.mw.admin.api.util.SensitiveType;

/**
 * This service's own type for {@code auth.wallet-lock.v1} (contract owned by mw-auth, ticket 70): the
 * approved ops unlock, so mw-auth lifts its PIN lock (every level) and any ADMIN lock. Key the wallet
 * id; mw-auth applies it once per {@code commandId} ({@code <changeRequestId>:auth-unlock}).
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
    String correlationId,
    Instant issuedAt) {

  public static final String UNLOCK = "UNLOCK";

  /** Who decided it: the checker ({@code user}). */
  public record Actor(String type, String id) {}
}
