package mfs.mw.admin.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.admin.api.entity.ContentVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code admin.content_version} (ticket 39; own schema only, spec 16.2), plain JDBC. */
@Repository
public class ContentVersionRepository {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final String COLUMNS =
      "config_key, version, config_type, value::text as value, status, change_request_id, maker_id, checker_id,"
          + " created_at, decided_at";

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public ContentVersionRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** The number the next version of {@code configKey} gets (1 for the first). */
  public int nextVersion(String configKey) {
    Integer max = jdbc.queryForObject("select max(version) from content_version where config_key = ?", Integer.class, configKey);
    return max == null ? 1 : max + 1;
  }

  public void insert(ContentVersion v) {
    jdbc.update(
        "insert into content_version (config_key, version, config_type, value, status, change_request_id, maker_id,"
            + " created_at) values (?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
        v.configKey(), v.version(), v.configType(), json.writeValueAsString(v.value()), v.status().name(),
        v.changeRequestId(), v.makerId(), Timestamp.from(v.createdAt()));
  }

  /** Records the decision on the version of a change request; {@code value} is what was (or would be) broadcast. */
  public void decide(UUID changeRequestId, ContentVersion.Status to, String checkerId, Map<String, Object> value, Instant at) {
    jdbc.update(
        "update content_version set status = ?, checker_id = ?, value = ?::jsonb, decided_at = ?"
            + " where change_request_id = ? and status = 'PENDING'",
        to.name(), checkerId, json.writeValueAsString(value), Timestamp.from(at), changeRequestId);
  }

  public Optional<ContentVersion> byChangeRequest(UUID changeRequestId) {
    return jdbc.query("select " + COLUMNS + " from content_version where change_request_id = ?", this::row, changeRequestId)
        .stream().findFirst();
  }

  public Optional<ContentVersion> find(String configKey, int version) {
    return jdbc.query(
            "select " + COLUMNS + " from content_version where config_key = ? and version = ?", this::row, configKey, version)
        .stream().findFirst();
  }

  /** The latest PUBLISHED version, if any. */
  public Optional<ContentVersion> latestPublished(String configKey) {
    return jdbc.query(
            "select " + COLUMNS + " from content_version where config_key = ? and status = 'PUBLISHED'"
                + " order by version desc limit 1",
            this::row, configKey)
        .stream().findFirst();
  }

  /** Newest first. */
  public List<ContentVersion> list(String configKey, int limit, int offset) {
    return jdbc.query(
        "select " + COLUMNS + " from content_version where config_key = ? order by version desc limit ? offset ?",
        this::row, configKey, limit, offset);
  }

  private ContentVersion row(ResultSet rs, int i) throws SQLException {
    Timestamp decided = rs.getTimestamp("decided_at");
    return new ContentVersion(
        rs.getString("config_key"),
        rs.getInt("version"),
        rs.getString("config_type"),
        json.readValue(rs.getString("value"), MAP),
        ContentVersion.Status.valueOf(rs.getString("status")),
        rs.getObject("change_request_id", UUID.class),
        rs.getString("maker_id"),
        rs.getString("checker_id"),
        rs.getTimestamp("created_at").toInstant(),
        decided == null ? null : decided.toInstant());
  }
}
