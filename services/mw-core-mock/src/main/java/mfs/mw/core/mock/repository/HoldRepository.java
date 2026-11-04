package mfs.mw.core.mock.repository;

import java.util.Optional;
import mfs.mw.core.mock.entity.Hold;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Hold rows of the {@code coremock} schema. */
@Repository
public class HoldRepository {

  private static final RowMapper<Hold> MAPPER =
      (rs, i) ->
          new Hold(
              rs.getString("id"),
              rs.getString("wallet_id"),
              rs.getString("txn_id"),
              rs.getLong("amount_minor"),
              rs.getLong("fee_minor"),
              rs.getString("status"));

  private final JdbcTemplate jdbc;

  public HoldRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(String id, String walletId, String txnId, long amountMinor, long feeMinor) {
    jdbc.update(
        "insert into hold (id, wallet_id, txn_id, amount_minor, fee_minor, status) values (?, ?, ?, ?, ?, 'HELD')",
        id,
        walletId,
        txnId,
        amountMinor,
        feeMinor);
  }

  /** Reads and row-locks a hold for the rest of the transaction. */
  public Optional<Hold> lock(String id) {
    return jdbc.query("select * from hold where id = ? for update", MAPPER, id).stream().findFirst();
  }

  public Optional<Hold> latestForTxn(String txnId) {
    return jdbc.query("select * from hold where txn_id = ? order by created_at desc, id desc limit 1", MAPPER, txnId)
        .stream()
        .findFirst();
  }

  public void setStatus(String id, String status) {
    jdbc.update("update hold set status = ? where id = ?", status, id);
  }
}
