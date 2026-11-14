package mfs.mw.crypto.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code crypto.key_change}: approved key changes applied once per change request (V5__key_ceremony.sql). */
@Repository
public class KeyChangeRepository {

  /** One applied change. */
  public record Applied(UUID changeRequestId, String action, String keyId, Integer version) {}

  private final JdbcClient jdbc;

  public KeyChangeRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Applied> find(UUID id) {
    return jdbc.sql("SELECT change_request_id, action, key_id, version FROM key_change WHERE change_request_id = :id")
        .param("id", id)
        .query((rs, n) -> new Applied(rs.getObject("change_request_id", UUID.class), rs.getString("action"),
            rs.getString("key_id"), (Integer) rs.getObject("version")))
        .optional();
  }

  /** Records the change; a second one with the same id throws DuplicateKeyException. */
  public void insert(UUID id, String action, String keyId, Integer version, String makerId, String checkerId, Instant at) {
    jdbc.sql("INSERT INTO key_change (change_request_id, action, key_id, version, maker_id, checker_id, applied_at)"
            + " VALUES (:id, :a, :k, :v, :m, :c, :t)")
        .param("id", id)
        .param("a", action)
        .param("k", keyId)
        .param("v", version)
        .param("m", makerId)
        .param("c", checkerId)
        .param("t", Timestamp.from(at))
        .update();
  }
}
