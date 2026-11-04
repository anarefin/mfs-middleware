package mfs.mw.notify.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import mfs.mw.notify.entity.TemplateRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code notify.template} (spec 10.3). */
@Repository
public class TemplateRepository {

  private final JdbcTemplate jdbc;

  public TemplateRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The highest ACTIVE version valid at {@code now}. */
  public Optional<TemplateRecord> findActive(String code, String channel, String locale, Instant now) {
    return jdbc.query(
            "select code, channel, locale, version, subject, body, sender_id from template"
                + " where code = ? and channel = ? and locale = ? and status = 'ACTIVE' and valid_from <= ?"
                + " order by version desc limit 1",
            (rs, i) -> new TemplateRecord(rs.getString("code"), rs.getString("channel"), rs.getString("locale"),
                rs.getInt("version"), rs.getString("subject"), rs.getString("body"), rs.getString("sender_id")),
            code, channel, locale, Timestamp.from(now))
        .stream()
        .findFirst();
  }
}
