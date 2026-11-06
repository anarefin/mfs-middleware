package mfs.mw.orchestrator.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code txn.idempotency}: scope (channel, initiator, key) to txnId and request hash (spec 7.3). */
@Repository
public class IdempotencyRepository {

  /** The first request seen for a key. */
  public record Stored(UUID txnId, byte[] requestHash) {

    public boolean sameRequest(byte[] hash) {
      return Arrays.equals(requestHash, hash);
    }
  }

  private final JdbcTemplate jdbc;

  public IdempotencyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Whether any scope holds {@code key} (the test-only API, ticket 58). */
  public boolean exists(String key) {
    Boolean found = jdbc.queryForObject("select exists(select 1 from idempotency where idem_key = ?)", Boolean.class, key);
    return Boolean.TRUE.equals(found);
  }

  /**
   * Claims the key for {@code txnId}. Returns empty when this request is the first (or the key
   * had expired and is now reused); otherwise the stored entry of the earlier request. The
   * primary key makes the claim safe under concurrency: a second insert waits for the first
   * transaction and then finds its row.
   */
  public Optional<Stored> claim(
      String channel, String initiatorId, String key, byte[] hash, UUID txnId, Instant now, Instant expiresAt) {
    int inserted =
        jdbc.update(
            "insert into idempotency (channel, initiator_id, idem_key, request_hash, txn_id, expires_at)"
                + " values (?, ?, ?, ?, ?, ?) on conflict do nothing",
            channel, initiatorId, key, hash, txnId, Timestamp.from(expiresAt));
    if (inserted == 1) {
      return Optional.empty();
    }
    int reused =
        jdbc.update(
            "update idempotency set request_hash = ?, txn_id = ?, expires_at = ?"
                + " where channel = ? and initiator_id = ? and idem_key = ? and expires_at <= ?",
            hash, txnId, Timestamp.from(expiresAt), channel, initiatorId, key, Timestamp.from(now));
    if (reused == 1) {
      return Optional.empty();
    }
    return jdbc
        .query(
            "select txn_id, request_hash from idempotency where channel = ? and initiator_id = ? and idem_key = ?",
            (rs, i) -> new Stored(rs.getObject("txn_id", UUID.class), rs.getBytes("request_hash")),
            channel, initiatorId, key)
        .stream()
        .findFirst();
  }
}
