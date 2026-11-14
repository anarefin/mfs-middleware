package mfs.mw.crypto.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code crypto.key_change} (V5, V7): one row per approval attempt id (mw-admin-api's claim token),
 * APPLIED with what was applied, or a terminal CANCELLED tombstone. Inserts are {@code ON CONFLICT DO
 * NOTHING}, so an apply and a cancel of the same id serialise on the primary key (the second waits
 * for the first to commit) and exactly one wins.
 */
@Repository
public class KeyChangeRepository {

  /** One attempt's row; {@code state} APPLIED or CANCELLED (a tombstone has no action or key). */
  public record Change(UUID changeId, String state, String action, String keyId, Integer version) {
    public boolean applied() {
      return "APPLIED".equals(state);
    }
  }

  private final JdbcClient jdbc;

  public KeyChangeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Change> find(UUID id) {
    return jdbc.sql("SELECT change_request_id, state, action, key_id, version FROM key_change WHERE change_request_id = :id")
        .param("id", id)
        .query((rs, n) -> new Change(rs.getObject("change_request_id", UUID.class), rs.getString("state"),
            rs.getString("action"), rs.getString("key_id"), (Integer) rs.getObject("version")))
        .optional();
  }

  /** Claims the id for an apply; false when it already has a row (applied or cancelled). */
  public boolean insertApplied(
      UUID id, String action, String keyId, Integer version, String makerId, String checkerId, Instant at) {
    return jdbc.sql("INSERT INTO key_change (change_request_id, state, action, key_id, version, maker_id, checker_id,"
            + " applied_at) VALUES (:id, 'APPLIED', :a, :k, :v, :m, :c, :t) ON CONFLICT (change_request_id) DO NOTHING")
        .param("id", id)
        .param("a", action)
        .param("k", keyId)
        .param("v", version)
        .param("m", makerId)
        .param("c", checkerId)
        .param("t", Timestamp.from(at))
        .update() == 1;
  }

  /** The terminal CANCELLED tombstone; false when the id already has a row. */
  public boolean insertCancelled(UUID id, Instant at) {
    return jdbc.sql("INSERT INTO key_change (change_request_id, state, applied_at) VALUES (:id, 'CANCELLED', :t)"
            + " ON CONFLICT (change_request_id) DO NOTHING")
        .param("id", id)
        .param("t", Timestamp.from(at))
        .update() == 1;
  }
}
