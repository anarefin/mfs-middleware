package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import mfs.mw.admin.api.util.Sensitive;
import mfs.mw.admin.api.util.SensitiveType;

/**
 * This service's own type for an {@code UnlockWallet} on {@code core.command.v1} (contract owned by
 * mw-orchestrator; message type added for ticket 52): sent to the core on {@code
 * core.wallet.command.v1} once an unlock request is approved. Key and txnId the request's {@code
 * unlockTxnId} (UUIDv7); the core is idempotent on {@code commandId} ({@code <changeRequestId>:unlock}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnlockWalletCommand(
    int schemaVersion,
    String commandType,
    String commandId,
    String txnId,
    String correlationId,
    Instant issuedAt,
    Instant expiresAt,
    Payload payload) {

  public static final String TYPE = "UnlockWallet";

  /** The wallet to unlock and who decided it. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(Subject subject, String changeRequestId, String makerId, String checkerId) {}

  /** {@code Party} of type WALLET; the id is the wallet's MSISDN. */
  public record Subject(String type, @Sensitive(SensitiveType.MSISDN) String id) {}
}
