package mfs.mw.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Table {@code atm_token} (V13, ticket 120b2): one ATM cash-out token per wallet, as an HMAC only.
 * A check is reserved with one conditional update (the mac still the one read, checks left), so
 * parallel guesses never exceed the checks; spending and voiding are conditional on the same mac.
 */
@Repository
public class AtmTokenRepository {

  /** The stored token: its HMAC (null once spent or void), key version, expiry and checks used. */
  public record Row(String mac, String macKid, Instant expiresAt, int attempts) {
    @Override
    public String toString() {
      return "Row[mac=****, macKid=" + macKid + ", expiresAt=" + expiresAt + ", attempts=" + attempts + "]";
    }
  }

  private final JdbcTemplate jdbc;

  public AtmTokenRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Row> find(String walletId) {
    List<Row> rows = jdbc.query(
        "SELECT mac, mac_kid, expires_at, attempts FROM atm_token WHERE wallet_id = ?",
        (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4)),
        walletId);
    return rows.stream().findFirst();
  }

  /** Stores a new token for the wallet, replacing any earlier one (with fresh checks). */
  public void issue(String walletId, String mac, String macKid, long amountMinor, String currency, Instant expiresAt,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO atm_token (wallet_id, mac, mac_kid, amount_minor, currency, expires_at, attempts, issued_at)
        VALUES (?, ?, ?, ?, ?, ?, 0, ?)
        ON CONFLICT (wallet_id) DO UPDATE SET mac = excluded.mac, mac_kid = excluded.mac_kid,
          amount_minor = excluded.amount_minor, currency = excluded.currency, expires_at = excluded.expires_at,
          attempts = 0, issued_at = excluded.issued_at
        """,
        walletId, mac, macKid, amountMinor, currency, Timestamp.from(expiresAt), Timestamp.from(now));
  }

  /** Takes one check of the live token whose HMAC is {@code mac}; false when none is left or it changed. */
  public boolean reserveAttempt(String walletId, String mac, int max, Instant now) {
    return jdbc.update(
        "UPDATE atm_token SET attempts = attempts + 1 WHERE wallet_id = ? AND mac = ? AND attempts < ? AND expires_at > ?",
        walletId, mac, max, Timestamp.from(now)) == 1;
  }

  /** Voids the token after the wrong guess that used its last check. */
  public void voidIfExhausted(String walletId, String mac, int max) {
    jdbc.update("UPDATE atm_token SET mac = NULL WHERE wallet_id = ? AND mac = ? AND attempts >= ?", walletId, mac, max);
  }

  /** Spends the token once; false when another check spent or voided it first. */
  public boolean consume(String walletId, String mac) {
    return jdbc.update("UPDATE atm_token SET mac = NULL WHERE wallet_id = ? AND mac = ?", walletId, mac) == 1;
  }
}
