package mfs.mw.notify.repository;

import java.sql.Timestamp;
import java.time.Instant;
import mfs.mw.notify.util.MsisdnHash;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code notify.opt_out} (ticket 111, NOT-09): the SHA-256 of the canonical MSISDN. */
@Repository
public class OptOutRepository {

  private final JdbcTemplate jdbc;

  public OptOutRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Records an opt-out. A repeat keeps the original {@code since}. */
  public void record(String msisdn, String channel, Instant since) {
    String canonical = MsisdnHash.canonical(msisdn);
    if (canonical == null) {
      return;
    }
    jdbc.update(
        "insert into opt_out (msisdn_hash, channel, since) values (?, ?, ?) on conflict (msisdn_hash) do nothing",
        MsisdnHash.sha256(canonical), channel, Timestamp.from(since));
  }

  public boolean optedOut(String msisdn) {
    String canonical = MsisdnHash.canonical(msisdn);
    if (canonical == null) {
      return false;
    }
    Integer n = jdbc.queryForObject(
        "select count(*) from opt_out where msisdn_hash = ?", Integer.class, MsisdnHash.sha256(canonical));
    return n != null && n > 0;
  }
}
