package mfs.mw.core.mock.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
              rs.getString("txn_type"),
              rs.getLong("amount_minor"),
              rs.getLong("fee_minor"),
              rs.getLong("vat_minor"),
              rs.getLong("commission_minor"),
              rs.getString("credit_wallet_id"),
              rs.getString("status"),
              rs.getTimestamp("expires_at").toInstant());

  private final JdbcTemplate jdbc;

  public HoldRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a HELD hold (ticket 94: its charges split, the wallet its commit credits and its expiry). */
  public void insert(Hold h) {
    jdbc.update(
        "insert into hold (id, wallet_id, txn_id, txn_type, amount_minor, fee_minor, vat_minor, commission_minor, "
            + "credit_wallet_id, status, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'HELD', ?)",
        h.id(), h.walletId(), h.txnId(), h.txnType(), h.amountMinor(), h.feeMinor(), h.vatMinor(), h.commissionMinor(),
        h.creditWalletId(), Timestamp.from(h.expiresAt()));
  }

  /** Reads and row-locks a hold for the rest of the transaction. */
  public Optional<Hold> lock(String id) {
    return jdbc.query("select * from hold where id = ? for update", MAPPER, id).stream().findFirst();
  }

  /** Reads a hold without locking it (the core wallet API finds the hold's txnId first, ticket 93). */
  public Optional<Hold> find(String id) {
    return jdbc.query("select * from hold where id = ?", MAPPER, id).stream().findFirst();
  }

  public Optional<Hold> latestForTxn(String txnId) {
    return jdbc.query("select * from hold where txn_id = ? order by created_at desc, id desc limit 1", MAPPER, txnId)
        .stream()
        .findFirst();
  }

  /** The ids of at most {@code limit} HELD holds whose expiry is not after {@code now}, oldest first (ticket 94). */
  public List<String> dueForExpiry(Instant now, int limit) {
    return jdbc.queryForList(
        "select id from hold where status = 'HELD' and expires_at <= ? order by expires_at, id limit ?",
        String.class, Timestamp.from(now), limit);
  }

  public void setStatus(String id, String status) {
    jdbc.update("update hold set status = ? where id = ?", status, id);
  }
}
