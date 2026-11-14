package mfs.mw.core.mock.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code coremock.used_assertion}: which transaction used an assertion's jti (ticket 71). */
@Repository
public class UsedAssertionRepository {

  private final JdbcTemplate jdbc;

  public UsedAssertionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Records {@code jti} for {@code txnId} unless it is recorded already; returns the txnId it is
   * recorded for afterwards (this one, or the one that used it first).
   */
  public String claim(String jti, String txnId, Instant expiresAt) {
    jdbc.update("insert into used_assertion (jti, txn_id, expires_at) values (?, ?, ?) on conflict (jti) do nothing",
        jti, txnId, Timestamp.from(expiresAt));
    return jdbc.queryForObject("select txn_id from used_assertion where jti = ?", String.class, jti);
  }

  /** The txnId that used {@code jti}, if any. */
  public Optional<String> txnOf(String jti) {
    return jdbc.queryForList("select txn_id from used_assertion where jti = ?", String.class, jti).stream().findFirst();
  }

  /** Deletes up to {@code batch} rows that expired before {@code before}; how many went. */
  public int purge(Instant before, int batch) {
    return jdbc.update(
        "delete from used_assertion where jti in (select jti from used_assertion where expires_at < ? limit ?)",
        Timestamp.from(before), batch);
  }
}
