package mfs.mw.core.mock.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import mfs.mw.core.mock.entity.LedgerEntry;
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

  /** Every entry posted under {@code txnId}. */
  public List<LedgerEntry> entriesOf(String txnId) {
    return jdbc.query(
        "select wallet_id, direction, kind, amount_minor from entry where txn_id = ? order by id",
        (rs, i) -> new LedgerEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
        txnId);
  }

  public boolean isReversed(String originalTxnId) {
    Integer n = jdbc.queryForObject("select count(*) from reversal where original_txn_id = ?", Integer.class, originalTxnId);
    return n != null && n > 0;
  }

  public void markReversed(String originalTxnId, String reversalTxnId) {
    jdbc.update("insert into reversal (original_txn_id, reversal_txn_id) values (?, ?)", originalTxnId, reversalTxnId);
  }
}
