package mfs.mw.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.dto.PinHmac;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The PIN state of a wallet (own schema only; spec 18.7, D20): {@code credential} (the PIN HMAC
 * under mw-crypto's PIN_HMAC key, never the PIN), {@code attempt} (wrong-PIN counter) and {@code
 * lock}. Ticket 69 counts failures and reads locks; the lockout levels are ticket 70's.
 */
@Repository
public class CredentialRepository {

  /** A lock row: kind PIN or ADMIN, its level, and until when (null = until unlocked or reset). */
  public record Lock(String kind, int level, Instant until) {}

  private final JdbcTemplate jdbc;

  public CredentialRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<PinHmac> find(String walletId) {
    return jdbc.query("select pin_hmac, hmac_key_id from credential where wallet_id = ?",
            (rs, i) -> new PinHmac(rs.getString(1), rs.getString(2)), walletId)
        .stream()
        .findFirst();
  }

  /** Stores the wallet's first PIN; false, changing nothing, when it already has one. */
  public boolean insertIfAbsent(String walletId, PinHmac pin, Instant at) {
    return jdbc.update(
            "insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values (?, ?, ?, ?)"
                + " on conflict (wallet_id) do nothing",
            walletId, pin.hmac(), pin.hmacKeyId(), Timestamp.from(at))
        == 1;
  }

  /** A rotated PIN_HMAC key: the value under the ACTIVE version replaces the stored one. */
  public void rehash(String walletId, PinHmac pin) {
    jdbc.update("update credential set pin_hmac = ?, hmac_key_id = ? where wallet_id = ?",
        pin.hmac(), pin.hmacKeyId(), walletId);
  }

  /** Counts a wrong PIN, atomically; the count of the current window. */
  public int countFailure(String walletId, Instant at) {
    Integer n = jdbc.queryForObject(
        "insert into attempt (wallet_id, window_started_at, count, updated_at) values (?, ?, 1, ?)"
            + " on conflict (wallet_id) do update set count = attempt.count + 1, updated_at = excluded.updated_at"
            + " returning count",
        Integer.class, walletId, Timestamp.from(at), Timestamp.from(at));
    return n == null ? 0 : n;
  }

  /** A correct PIN resets the counter (not the lock history: ticket 70). */
  public void resetFailures(String walletId) {
    jdbc.update("delete from attempt where wallet_id = ?", walletId);
  }

  public List<Lock> locks(String walletId) {
    return jdbc.query("select kind, level, until from lock where wallet_id = ?",
        (rs, i) -> {
          Timestamp until = rs.getTimestamp(3);
          return new Lock(rs.getString(1), rs.getInt(2), until == null ? null : until.toInstant());
        },
        walletId);
  }
}
