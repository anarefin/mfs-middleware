package mfs.mw.adapter.atm.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Table {@code atm_inbound} (V8): one row per (operation, atmTxnRef) the switch sent (ticket 120a). */
@Repository
public class AtmInboundRepository {

  /** A recorded inbound ATM call. */
  public record Row(String txnId, String inboundId) {}

  private final JdbcTemplate jdbc;

  public AtmInboundRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the row unless one exists; true when this call inserted it. */
  public boolean insert(String operation, String atmTxnRef, String txnId, String inboundId, Instant at) {
    return jdbc.update(
        "insert into atm_inbound (operation, atm_txn_ref, txn_id, inbound_id, received_at) "
            + "values (?, ?, ?, ?, ?) on conflict do nothing",
        operation, atmTxnRef, txnId, inboundId, Timestamp.from(at)) == 1;
  }

  public Optional<Row> find(String operation, String atmTxnRef) {
    List<Row> rows = jdbc.query(
        "select txn_id, inbound_id from atm_inbound where operation = ? and atm_txn_ref = ?",
        (rs, i) -> new Row(rs.getString(1), rs.getString(2)),
        operation, atmTxnRef);
    return rows.stream().findFirst();
  }
}
