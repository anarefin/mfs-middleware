package mfs.mw.admin.api.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The approved routing projection (ticket 98). The orchestrator keeps the copy it routes from. */
@Repository
public class RoutingResourceRepository {

  public record Row(String resourceType, String resourceId, int version, String value) {}

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public RoutingResourceRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<Row> findForUpdate(String resourceType, String resourceId) {
    return jdbc.query(
            "select resource_type, resource_id, version, value::text from routing_resource"
                + " where resource_type = ? and resource_id = ? for update",
            (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
            resourceType,
            resourceId)
        .stream()
        .findFirst();
  }

  public void save(String resourceType, String resourceId, int version, Object value, Instant at) {
    jdbc.update(
        "insert into routing_resource (resource_type, resource_id, version, value, updated_at)"
            + " values (?, ?, ?, ?::jsonb, ?)"
            + " on conflict (resource_type, resource_id) do update set version = excluded.version,"
            + " value = excluded.value, updated_at = excluded.updated_at",
        resourceType,
        resourceId,
        version,
        value == null ? null : json.writeValueAsString(value),
        Timestamp.from(at));
  }

  public void delete(String resourceType, String resourceId) {
    jdbc.update("delete from routing_resource where resource_type = ? and resource_id = ?", resourceType, resourceId);
  }
}
