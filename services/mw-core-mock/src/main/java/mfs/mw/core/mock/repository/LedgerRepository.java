package mfs.mw.core.mock.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Double-entry rows and core references. */
@Repository
public class LedgerRepository {

  private final JdbcTemplate jdbc;

  public LedgerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void append(
      String commandId, String txnId, String walletId, String direction, String kind, long amountMinor, Instant at) {
    jdbc.update(
        "insert into entry (command_id, txn_id, wallet_id, direction, kind, amount_minor, created_at) "
            + "values (?, ?, ?, ?, ?, ?, ?)",
        commandId,
        txnId,
        walletId,
        direction,
        kind,
        amountMinor,
        Timestamp.from(at));
  }

  public long nextCoreSequence() {
    Long n = jdbc.queryForObject("select nextval('core_ref_seq')", Long.class);
    return n == null ? 0 : n;
  }
}
