package mfs.mw.core.mock.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.entity.WalletState;
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
              rs.getLong("held_minor"));

  private static final RowMapper<WalletState> STATE =
      (rs, i) ->
          new WalletState(
              rs.getString("id"),
              rs.getString("msisdn"),
              rs.getString("type"),
              rs.getString("status"),
              rs.getLong("version"));

  private static final String STATE_COLUMNS = "select id, msisdn, type, status, version from wallet ";

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

  /**
   * Inserts an ACTIVE wallet whose id and MSISDN are {@code id} (test-only seed API, ticket 57);
   * false, changing nothing, when one with that id already exists.
   */
  public boolean insertIfAbsent(String id, String type, long balanceMinor) {
    return jdbc.update(
            "insert into wallet (id, msisdn, type, balance_minor) values (?, ?, ?, ?) on conflict do nothing",
            id, id, type, balanceMinor)
        == 1;
  }

  /** The published state of a wallet with an MSISDN (ticket 67), empty for a system wallet. */
  public Optional<WalletState> state(String id) {
    return jdbc.query(STATE_COLUMNS + "where id = ? and msisdn is not null", STATE, id).stream().findFirst();
  }

  /** The published state of every wallet with an MSISDN, by id (snapshot, ticket 67). */
  public List<WalletState> states() {
    return jdbc.query(STATE_COLUMNS + "where msisdn is not null order by id", STATE);
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

  public void adjustHeld(String id, long deltaMinor) {
    jdbc.update("update wallet set held_minor = held_minor + ? where id = ?", deltaMinor, id);
  }

  /**
   * Ops unlock (ticket 52): a core-LOCKED wallet back to ACTIVE (since ticket 72 a core-side lock,
   * not a PIN lockout). Returns false, changing nothing, when the wallet is not LOCKED (active, or
   * blocked otherwise).
   */
  public boolean unlock(String id) {
    return jdbc.update("update wallet set status = 'ACTIVE', version = nextval('wallet_version_seq') "
                + "where id = ? and status = 'LOCKED'", id) == 1;
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
