package mfs.mw.adapter.atm.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Table {@code atm_inbound} (V8, V9): one row per (operation, atmTxnRef) the switch sent (tickets
 * 120a, 120b1). A state moves with a compare-and-set update, so a confirm racing a reversal of one
 * cash-out lets exactly one of them win.
 */
@Repository
public class AtmInboundRepository implements AtmInboundStore {

  private final JdbcTemplate jdbc;

  public AtmInboundRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insert(String operation, String atmTxnRef, Row row, Instant at) {
    return jdbc.update(
        "insert into atm_inbound (operation, atm_txn_ref, txn_id, inbound_id, received_at, atm_id, fingerprint, state) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
        operation, atmTxnRef, row.txnId(), row.inboundId(), Timestamp.from(at),
        row.atmId(), row.fingerprint(), row.state()) == 1;
  }

  @Override
  public Optional<Row> find(String operation, String atmTxnRef) {
    List<Row> rows = jdbc.query(
        "select txn_id, inbound_id, atm_id, fingerprint, state from atm_inbound "
            + "where operation = ? and atm_txn_ref = ?",
        (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
        operation, atmTxnRef);
    return rows.stream().findFirst();
  }

  @Override
  public boolean transition(String operation, String atmTxnRef, String from, String to) {
    return jdbc.update(
        "update atm_inbound set state = ? where operation = ? and atm_txn_ref = ? and state = ?",
        to, operation, atmTxnRef, from) == 1;
  }
}
