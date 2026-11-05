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

  /** The highest admin version (ticket 39) stored for this template; empty when none was published. */
  public Optional<Long> latestConfigVersion(String code, String channel, String locale) {
    return Optional.ofNullable(
        jdbc.queryForObject(
            "select max(config_version) from template where code = ? and channel = ? and locale = ?",
            Long.class, code, channel, locale));
  }

  /**
   * Stores a version published by the admin console as the next version of the template, ACTIVE
   * from {@code validFrom}; returns the version it got.
   */
  public int insertPublished(
      String code, String channel, String locale, long configVersion, String subject, String body, String htmlBody,
      String senderId, Instant validFrom) {
    Integer max =
        jdbc.queryForObject(
            "select max(version) from template where code = ? and channel = ? and locale = ?",
            Integer.class, code, channel, locale);
    int version = max == null ? 1 : max + 1;
    jdbc.update(
        "insert into template (code, channel, locale, version, subject, body, html_body, sender_id, status, valid_from,"
            + " config_version) values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
        code, channel, locale, version, subject, body, htmlBody, senderId, Timestamp.from(validFrom), configVersion);
    return version;
  }

  /** The highest ACTIVE version valid at {@code now}. */
  public Optional<TemplateRecord> findActive(String code, String channel, String locale, Instant now) {
    return jdbc.query(
            "select code, channel, locale, version, subject, body, sender_id, html_body from template"
                + " where code = ? and channel = ? and locale = ? and status = 'ACTIVE' and valid_from <= ?"
                + " order by version desc limit 1",
            (rs, i) -> new TemplateRecord(rs.getString("code"), rs.getString("channel"), rs.getString("locale"),
                rs.getInt("version"), rs.getString("subject"), rs.getString("body"), rs.getString("sender_id"), rs.getString("html_body")),
            code, channel, locale, Timestamp.from(now))
        .stream()
        .findFirst();
  }
}
