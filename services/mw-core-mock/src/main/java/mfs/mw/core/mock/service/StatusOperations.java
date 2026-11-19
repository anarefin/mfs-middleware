package mfs.mw.core.mock.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.QueryPayload;
import mfs.mw.core.mock.entity.Hold;
import mfs.mw.core.mock.mapper.CoreCommandMapper;
import mfs.mw.core.mock.mapper.CoreEventMapper;
import mfs.mw.core.mock.repository.CommandLogRepository;
import mfs.mw.core.mock.repository.HoldRepository;
import mfs.mw.core.mock.repository.LedgerRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * QueryTransaction: the core's true view of a transaction, whatever happened to the reply of the
 * command that changed it. Reads the ledger and the command log, never the outbox, so a dropped
 * reply is still answered. Without {@code queryCommandId}: reversed, else the hold's state, else
 * the outcome of the latest command; with it: the outcome of that command only.
 */
@Component
class StatusOperations {

  private final CommandLogRepository commandLog;
  private final HoldRepository holds;
  private final LedgerRepository ledger;
  private final CoreCommandMapper commands;
  private final CoreEventMapper events;
  private final JsonMapper json;

  StatusOperations(
      CommandLogRepository commandLog,
      HoldRepository holds,
      LedgerRepository ledger,
      CoreCommandMapper commands,
      CoreEventMapper events,
      JsonMapper json) {
    this.commandLog = commandLog;
    this.holds = holds;
    this.ledger = ledger;
    this.commands = commands;
    this.events = events;
    this.json = json;
  }

  CoreEvent query(CoreCommand cmd, String coreRef) {
    QueryPayload p = commands.query(cmd);
    String status = "NOT_FOUND";
    String holdId = null;
    if (p.queryCommandId() != null && !p.queryCommandId().isBlank()) {
      Optional<String> stored = commandLog.findEvent(p.queryCommandId());
      if (stored.isPresent()) {
        JsonNode e = json.readTree(stored.get());
        status = statusOf(e.path("eventType").asString());
        holdId = e.path("payload").path("holdId").asString(null);
      }
    } else {
      TxnStatus current = current(cmd.txnId());
      status = current.status();
      holdId = current.holdId();
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("txnStatus", status);
    if (holdId != null) {
      payload.put("holdId", holdId);
    }
    return events.succeeded(cmd, "TransactionStatus", coreRef, null, payload);
  }

  /** A transaction's status and the hold it has, if any. */
  record TxnStatus(String status, String holdId) {}

  /**
   * The core's view of {@code txnId} (QueryTransaction without queryCommandId, and WAL-13): reversed,
   * else the latest hold's state, else the latest non-query command's outcome, else NOT_FOUND.
   * Reads only; changes nothing.
   */
  TxnStatus current(String txnId) {
    if (ledger.isReversed(txnId)) {
      return new TxnStatus("REVERSED", holds.latestForTxn(txnId).map(Hold::id).orElse(null));
    }
    Optional<Hold> hold = holds.latestForTxn(txnId);
    if (hold.isPresent()) {
      return new TxnStatus(hold.get().status().equals(Hold.HELD) ? "HELD" : hold.get().status(), hold.get().id());
    }
    return commandLog.latestEventOfTxn(txnId)
        .map(e -> new TxnStatus(statusOf(json.readTree(e).path("eventType").asString()), null))
        .orElse(new TxnStatus("NOT_FOUND", null));
  }

  static String statusOf(String eventType) {
    return switch (eventType) {
      case "TransactionValidated" -> "VALIDATED";
      case "FundsHeld" -> "HELD";
      case "HoldCommitted" -> "COMMITTED";
      case "HoldReleased" -> "RELEASED";
      case "TransferPosted" -> "POSTED";
      case "WalletCredited" -> "CREDITED";
      case "TransactionReversed" -> "REVERSED";
      default -> "REJECTED";
    };
  }
}
