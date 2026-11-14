package mfs.mw.auth.repository;

import java.util.Optional;
import mfs.mw.auth.entity.AuthWallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** {@code auth.wallet}, the projection of the core's wallets (ticket 67, D5). */
@Repository
public class WalletRepository {

  private static final RowMapper<AuthWallet> MAPPER =
      (rs, i) ->
          new AuthWallet(
              rs.getString("wallet_id"),
              rs.getString("msisdn_token"),
              rs.getString("msisdn_token_kid"),
              rs.getString("msisdn_enc"),
              rs.getString("type"),
              rs.getString("status"),
              rs.getBoolean("has_pin"),
              rs.getObject("birth_year", Integer.class),
              rs.getLong("version"));

  private static final String COLUMNS =
      "select wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc::text msisdn_enc, type, status, has_pin, "
          + "birth_year, version from wallet ";

  private final JdbcTemplate jdbc;

  public WalletRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AuthWallet> find(String walletId) {
    return jdbc.query(COLUMNS + "where wallet_id = ?", MAPPER, walletId).stream().findFirst();
  }

  /** The wallet whose MSISDN has this keyed hash (login lookup, tickets 68-69). */
  public Optional<AuthWallet> findByMsisdnToken(String msisdnToken) {
    return jdbc.query(COLUMNS + "where msisdn_token = ?", MAPPER, msisdnToken).stream().findFirst();
  }

  /** The stored version of a wallet, empty when it is not projected yet. */
  public Optional<Long> version(String walletId) {
    return jdbc.query("select version from wallet where wallet_id = ?", (rs, i) -> rs.getLong(1), walletId)
        .stream()
        .findFirst();
  }

  /**
   * Replaces the state of a projected wallet (type, status, PIN flag, birth year, version) when
   * {@code w.version()} is higher; the MSISDN columns of {@code w} are not used. False, changing
   * nothing, for an equal or lower version or an unknown wallet.
   */
  public boolean updateIfNewer(AuthWallet w) {
    return jdbc.update(
            "update wallet set type = ?, status = ?, has_pin = ?, birth_year = ?, version = ?, updated_at = now() "
                + "where wallet_id = ? and version < ?",
            w.type(),
            w.status(),
            w.hasPin(),
            w.birthYear(),
            w.version(),
            w.walletId(),
            w.version())
        == 1;
  }

  /**
   * Inserts the wallet, or replaces its state (type, status, PIN flag, birth year, version) when
   * {@code w.version()} is higher than the stored one; the MSISDN columns are kept as first written.
   * Returns false, changing nothing, for an equal or lower version.
   */
  public boolean upsertIfNewer(AuthWallet w) {
    return jdbc.update(
            "insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, "
                + "birth_year, version) values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) "
                + "on conflict (wallet_id) do update set type = excluded.type, status = excluded.status, "
                + "has_pin = excluded.has_pin, birth_year = excluded.birth_year, version = excluded.version, "
                + "updated_at = now() where wallet.version < excluded.version",
            w.walletId(),
            w.msisdnToken(),
            w.msisdnTokenKid(),
            w.msisdnEnc(),
            w.type(),
            w.status(),
            w.hasPin(),
            w.birthYear(),
            w.version())
        == 1;
  }
}
