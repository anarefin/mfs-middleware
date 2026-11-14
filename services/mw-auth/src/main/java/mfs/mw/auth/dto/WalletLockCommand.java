package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** A record of {@code auth.wallet-lock.v1} (owned by this service, ticket 70); unknown fields ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletLockCommand(
    int schemaVersion,
    String commandId,
    String action,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    @Sensitive(SensitiveType.SECRET) String reason,
    Actor actor,
    String changeRequestId,
    String makerId,
    String correlationId,
    Instant issuedAt) {

  public static final String LOCK = "LOCK";
  public static final String UNLOCK = "UNLOCK";
  public static final String REVOKE_SESSIONS = "REVOKE_SESSIONS";
  /** An approved care PIN reset (security review of ticket 70: committed with the approval). */
  public static final String PIN_RESET = "PIN_RESET";

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Actor(String type, String id) {}

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
