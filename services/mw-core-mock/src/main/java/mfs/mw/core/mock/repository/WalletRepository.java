package mfs.mw.core.mock.repository;

import java.time.Instant;
import java.util.Optional;
import mfs.mw.core.mock.entity.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Wallet rows of the {@code coremock} schema. */
@Repository
public class WalletRepository {

  private static final RowMapper<Wallet> MAPPER =
      (rs, i) ->
          new Wallet(
              rs.getString("id"),
              rs.getString("msisdn"),
              rs.getString("type"),
              rs.getString("status"),
              rs.getLong("balance_minor"),
              rs.getLong("held_minor"),
              rs.getString("pin_hash"));

  private final JdbcTemplate jdbc;

  public WalletRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The id of the wallet whose id or MSISDN is {@code idOrMsisdn}. */
  public Optional<String> resolveId(String idOrMsisdn) {
    return jdbc.query("select id from wallet where id = ? or msisdn = ?", (rs, i) -> rs.getString(1), idOrMsisdn, idOrMsisdn)
        .stream()
        .findFirst();
  }

  /** Reads and row-locks a wallet for the rest of the transaction. */
  public Wallet lock(String id) {
    return jdbc.queryForObject("select * from wallet where id = ? for update", MAPPER, id);
  }

  public Wallet get(String id) {
    return jdbc.queryForObject("select * from wallet where id = ?", MAPPER, id);
  }

  public void adjustBalance(String id, long deltaMinor) {
    jdbc.update("update wallet set balance_minor = balance_minor + ? where id = ?", deltaMinor, id);
  }

  /** Sum of transfer debits of the wallet since {@code since}. */
  public long debitedSince(String id, Instant since) {
    Long sum =
        jdbc.queryForObject(
            "select coalesce(sum(amount_minor), 0) from entry "
                + "where wallet_id = ? and direction = 'DEBIT' and kind = 'TRANSFER' and created_at >= ?",
            Long.class,
            id,
            java.sql.Timestamp.from(since));
    return sum == null ? 0 : sum;
  }
}
