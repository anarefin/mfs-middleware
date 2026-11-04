package mfs.mw.core.mock.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The commandId deduplication log: the first result event of each command, verbatim JSON. */
@Repository
public class CommandLogRepository {

  private final JdbcTemplate jdbc;

  public CommandLogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Serialises work on one commandId until the transaction ends. */
  public void lock(String commandId) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, commandId);
  }

  public Optional<String> findEvent(String commandId) {
    return jdbc.query("select event::text from command_log where command_id = ?", (rs, i) -> rs.getString(1), commandId)
        .stream()
        .findFirst();
  }

  public void save(String commandId, String commandType, String txnId, String eventJson) {
    jdbc.update(
        "insert into command_log (command_id, command_type, txn_id, event) values (?, ?, ?, ?::jsonb)",
        commandId,
        commandType,
        txnId,
        eventJson);
  }

  /** The result event of the latest command of {@code txnId}, status inquiries excluded. */
  public Optional<String> latestEventOfTxn(String txnId) {
    return jdbc.query(
            "select event::text from command_log where txn_id = ? and command_type <> 'QueryTransaction' "
                + "order by seq desc limit 1",
            (rs, i) -> rs.getString(1),
            txnId)
        .stream()
        .findFirst();
  }
}
