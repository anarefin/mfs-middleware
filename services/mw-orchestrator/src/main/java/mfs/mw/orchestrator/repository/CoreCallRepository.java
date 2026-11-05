package mfs.mw.orchestrator.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code txn.core_call} (B13): the core leg of the saga timeline, one row per command with its
 * result. Written in the saga's transaction, read by the query API only ({@link
 * TransactionRepository#timeline}); the saga never decides anything from it.
 */
@Repository
public class CoreCallRepository {

  private final JdbcTemplate jdbc;

  public CoreCallRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A command sent to the core for transaction {@code txnId} (the original's txnId for a
   * ReverseTransaction, which goes under the reversal's). A resend under the same {@code
   * commandId} keeps the first row and counts the send.
   */
  public void sent(String commandId, UUID txnId, String commandType, Instant at) {
    jdbc.update(
        "insert into core_call (command_id, txn_id, command_type, sent_at, last_sent_at) values (?, ?, ?, ?, ?)"
            + " on conflict (command_id) do update set sends = core_call.sends + 1, last_sent_at = excluded.last_sent_at",
        commandId, txnId, commandType, Timestamp.from(at), Timestamp.from(at));
  }

  /**
   * The core's result for {@code commandId}: its event type, outcome and reason. The first result
   * is kept (the core answers a command once; a replay changes nothing); a result for a command
   * this table never saw changes nothing.
   */
  public void answered(String commandId, String result, String outcome, String reasonCode, Instant at) {
    jdbc.update(
        "update core_call set result = ?, outcome = ?, reason_code = ?, answered_at = ?"
            + " where command_id = ? and answered_at is null",
        result, outcome, reasonCode, Timestamp.from(at), commandId);
  }
}
