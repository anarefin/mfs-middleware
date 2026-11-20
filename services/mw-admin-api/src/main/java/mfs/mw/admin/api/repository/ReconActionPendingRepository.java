package mfs.mw.admin.api.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Approved {@code recon_exception} actions mw-recon has not acknowledged yet (REC-05, M9 review): written in the
 * decision transaction, deleted once mw-recon answered.
 */
@Repository
public class ReconActionPendingRepository {

  /** One approved action waiting for mw-recon. */
  public record Pending(UUID changeRequestId, UUID exceptionId, String action, String checkerId, int attempts) {}

  private final JdbcTemplate jdbc;

  public ReconActionPendingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID changeRequestId, UUID exceptionId, String action, String checkerId, Instant at) {
    jdbc.update("""
        insert into recon_action_pending (change_request_id, exception_id, action, checker_id, created_at)
        values (?, ?, ?, ?, ?) on conflict (change_request_id) do nothing
        """, changeRequestId, exceptionId, action, checkerId, Timestamp.from(at));
  }

  /** Rows never tried, or last tried before {@code before}, oldest first. */
  public List<Pending> due(Instant before, int limit) {
    return jdbc.query("""
        select change_request_id, exception_id, action, checker_id, attempts from recon_action_pending
        where last_attempt_at is null or last_attempt_at < ? order by created_at limit ?
        """, (rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getString(4), rs.getInt(5)), Timestamp.from(before), limit);
  }

  public void attempted(UUID changeRequestId, Instant at) {
    jdbc.update("update recon_action_pending set attempts = attempts + 1, last_attempt_at = ? where change_request_id = ?",
        Timestamp.from(at), changeRequestId);
  }

  public void delete(UUID changeRequestId) {
    jdbc.update("delete from recon_action_pending where change_request_id = ?", changeRequestId);
  }
}
