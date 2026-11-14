package mfs.mw.auth.mapper;

import java.util.Set;
import mfs.mw.auth.dto.WalletLockCommand;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code auth.wallet-lock.v1} records (ticket 70). A record without what the consumer needs
 * (schemaVersion 1, commandId, a known action, walletId, reason, actor id) is malformed ({@code
 * MW-VAL-002}: retry topics, then the DLQ).
 */
@Component
public class WalletLockMapper {

  private static final Set<String> ACTIONS =
      Set.of(WalletLockCommand.LOCK, WalletLockCommand.UNLOCK, WalletLockCommand.REVOKE_SESSIONS);

  private final JsonMapper json;

  public WalletLockMapper(JsonMapper json) {
    this.json = json;
  }

  public WalletLockCommand read(String body) {
    WalletLockCommand cmd;
    try {
      cmd = json.readValue(body, WalletLockCommand.class);
    } catch (JacksonException e) {
      throw malformed("wallet lock command unreadable");
    }
    if (cmd.schemaVersion() != 1 || blank(cmd.commandId()) || blank(cmd.walletId()) || !ACTIONS.contains(cmd.action())
        || blank(cmd.reason()) || cmd.actor() == null || blank(cmd.actor().id())) {
      throw malformed("wallet lock command lacks a required field");
    }
    return cmd;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static MwException malformed(String message) {
    return new MwException(ErrorCodes.VAL_MALFORMED, message);
  }
}
