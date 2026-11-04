package mfs.mw.orchestrator.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code txn.saga_timer}: one deadline per transaction and step (spec 7.1, 16.3). */
@Repository
public class SagaTimerRepository {

  /** A deadline that has passed. */
  public record Due(UUID txnId, String step, int attempt) {}

  private final JdbcTemplate jdbc;

  public SagaTimerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Arms (or re-arms, counting the attempt) the deadline of {@code step}. */
  public void arm(UUID txnId, String step, Instant dueAt, boolean retry) {
    jdbc.update(
        "insert into saga_timer (txn_id, step, due_at, attempt) values (?, ?, ?, 0)"
            + " on conflict (txn_id, step) do update set due_at = excluded.due_at,"
            + " attempt = case when ?::boolean then saga_timer.attempt + 1 else 0 end",
        txnId,
        step,
        Timestamp.from(dueAt),
        retry);
  }

  /**
   * Arms {@code step} with an explicit attempt number (the status-inquiry slot, spec 7.4: the
   * sweep deletes a timer before handling it, so the caller carries the count over).
   */
  public void armAttempt(UUID txnId, String step, Instant dueAt, int attempt) {
    jdbc.update(
        "insert into saga_timer (txn_id, step, due_at, attempt) values (?, ?, ?, ?)"
            + " on conflict (txn_id, step) do update set due_at = excluded.due_at, attempt = excluded.attempt",
        txnId,
        step,
        Timestamp.from(dueAt),
        attempt);
  }

  public void clear(UUID txnId) {
    jdbc.update("delete from saga_timer where txn_id = ?", txnId);
  }

  /**
   * Locks up to {@code limit} timers due at {@code now}, oldest first, skipping rows another
   * sweeper holds ({@code FOR UPDATE SKIP LOCKED}, spec 16.3). The locks last until the caller's
   * transaction ends.
   */
  public List<Due> lockDue(Instant now, int limit) {
    return jdbc.query(
        "select txn_id, step, attempt from saga_timer where due_at <= ? order by due_at limit ?"
            + " for update skip locked",
        (rs, i) -> new Due(rs.getObject("txn_id", UUID.class), rs.getString("step"), rs.getInt("attempt")),
        Timestamp.from(now),
        limit);
  }

  public void delete(UUID txnId, String step) {
    jdbc.update("delete from saga_timer where txn_id = ? and step = ?", txnId, step);
  }
}
