package mfs.mw.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.dto.PinHmac;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The PIN state of a wallet (own schema only; spec 18.7, D20): {@code credential} (the PIN HMAC
 * under mw-crypto's PIN_HMAC key, never the PIN, and the HMACs of earlier PINs for the reuse rule),
 * {@code attempt} (wrong-PIN counter of the current level) and {@code lock} (PIN lockout levels and
 * ADMIN locks). The lockout's read-decide-write runs in one transaction under {@link #lockWallet}.
 */
@Repository
public class CredentialRepository {

  /** A lock row: kind PIN or ADMIN, its level, until when (null = until unlocked or reset), since when. */
  public record Lock(String kind, int level, Instant until, Instant lockedAt) {

    public Lock(String kind, int level, Instant until) {
      this(kind, level, until, null);
    }

    /** In force at {@code now}: no end, or an end still ahead. */
    public boolean activeAt(Instant now) {
      return until == null || until.isAfter(now);
    }
  }

  /** The current PIN and the earlier ones (newest first), each an HMAC with its key version. */
  public record Credential(PinHmac current, List<PinHmac> earlier) {}

  private static final TypeReference<List<PinHmac>> HMACS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public CredentialRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<PinHmac> find(String walletId) {
    return jdbc.query("select pin_hmac, hmac_key_id from credential where wallet_id = ?",
            (rs, i) -> new PinHmac(rs.getString(1), rs.getString(2)), walletId)
        .stream()
        .findFirst();
  }

  public Optional<Credential> credential(String walletId) {
    return jdbc.query("select pin_hmac, hmac_key_id, last_hmacs::text from credential where wallet_id = ?",
            (rs, i) -> new Credential(new PinHmac(rs.getString(1), rs.getString(2)), json.readValue(rs.getString(3), HMACS)),
            walletId)
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

  /** A new PIN (change, reset): replaces the current one, {@code earlier} the PINs kept for the reuse rule. */
  public void replace(String walletId, PinHmac pin, List<PinHmac> earlier, Instant at) {
    jdbc.update(
        "insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at, last_hmacs) values (?, ?, ?, ?, ?::jsonb)"
            + " on conflict (wallet_id) do update set pin_hmac = excluded.pin_hmac, hmac_key_id = excluded.hmac_key_id,"
            + " set_at = excluded.set_at, last_hmacs = excluded.last_hmacs",
        walletId, pin.hmac(), pin.hmacKeyId(), Timestamp.from(at), json.writeValueAsString(earlier));
  }

  /** A rotated PIN_HMAC key: the value under the ACTIVE version replaces the stored one. */
  public void rehash(String walletId, PinHmac pin) {
    jdbc.update("update credential set pin_hmac = ?, hmac_key_id = ? where wallet_id = ?",
        pin.hmac(), pin.hmacKeyId(), walletId);
  }

  /**
   * Takes the wallet's row lock for the rest of the transaction: every wrong-PIN decision of one
   * wallet runs one after the other (count, escalate, lock). False when the wallet is not known.
   */
  public boolean lockWallet(String walletId) {
    return !jdbc.queryForList("select 1 from wallet where wallet_id = ? for update", Integer.class, walletId).isEmpty();
  }

  /** Counts a wrong PIN, atomically; the count of the current level. */
  public int countFailure(String walletId, Instant at) {
    Integer n = jdbc.queryForObject(
        "insert into attempt (wallet_id, window_started_at, count, updated_at) values (?, ?, 1, ?)"
            + " on conflict (wallet_id) do update set count = attempt.count + 1, updated_at = excluded.updated_at"
            + " returning count",
        Integer.class, walletId, Timestamp.from(at), Timestamp.from(at));
    return n == null ? 0 : n;
  }

  /** A correct PIN (or a new lock level) resets the counter, not the lock history. */
  public void resetFailures(String walletId) {
    jdbc.update("delete from attempt where wallet_id = ?", walletId);
  }

  /** The wrong PINs counted at the current level (0 when none). */
  public int failures(String walletId) {
    return jdbc.queryForList("select count from attempt where wallet_id = ?", Integer.class, walletId).stream()
        .findFirst().orElse(0);
  }

  public List<Lock> locks(String walletId) {
    return jdbc.query("select kind, level, until, created_at from lock where wallet_id = ?",
        (rs, i) -> {
          Timestamp until = rs.getTimestamp(3);
          return new Lock(rs.getString(1), rs.getInt(2), until == null ? null : until.toInstant(),
              rs.getTimestamp(4).toInstant());
        },
        walletId);
  }

  public Optional<Lock> lock(String walletId, String kind) {
    return locks(walletId).stream().filter(l -> l.kind().equals(kind)).findFirst();
  }

  /** Sets the wallet's lock of {@code kind} (a new PIN level, or an ADMIN lock), replacing the row. */
  public void putLock(String walletId, String kind, int level, Instant until, String reason, String actor, Instant at) {
    jdbc.update(
        "insert into lock (wallet_id, kind, level, until, reason, actor, created_at) values (?, ?, ?, ?, ?, ?, ?)"
            + " on conflict (wallet_id, kind) do update set level = excluded.level, until = excluded.until,"
            + " reason = excluded.reason, actor = excluded.actor, created_at = excluded.created_at",
        walletId, kind, level, until == null ? null : Timestamp.from(until), reason, actor, Timestamp.from(at));
  }

  /** Removes the wallet's lock of {@code kind} (with a PIN lock, its level history); true when there was one. */
  public boolean removeLock(String walletId, String kind) {
    return jdbc.update("delete from lock where wallet_id = ? and kind = ?", walletId, kind) == 1;
  }
}
