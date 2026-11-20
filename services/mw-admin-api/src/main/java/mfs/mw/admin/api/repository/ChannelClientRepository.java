package mfs.mw.admin.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import mfs.mw.admin.api.dto.ChannelClientSpec;
import mfs.mw.admin.api.entity.ChannelClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code admin.channel_client} (ticket 100a), plain JDBC. */
@Repository
public class ChannelClientRepository {

  private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
  private static final String SELECT =
      "select client_id, client_type, auth_method, allowed_routes::text as allowed_routes,"
          + " ip_allow_list::text as ip_allow_list, rate_per_sec, daily_quota, enabled, version, updated_at,"
          + " updated_by, approved_by from channel_client";

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public ChannelClientRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<ChannelClient> find(String clientId) {
    return jdbc.query(SELECT + " where client_id = ?", this::row, clientId).stream().findFirst();
  }

  /** The row locked for the approving transaction. */
  public Optional<ChannelClient> findForUpdate(String clientId) {
    return jdbc.query(SELECT + " where client_id = ? for update", this::row, clientId).stream().findFirst();
  }

  public List<ChannelClient> list() {
    return jdbc.query(SELECT + " order by client_id", this::row);
  }

  /** @return false when the client exists already */
  public boolean insert(ChannelClient c) {
    ChannelClientSpec s = c.spec();
    return jdbc.update(
            "insert into channel_client (client_id, client_type, auth_method, allowed_routes, ip_allow_list,"
                + " rate_per_sec, daily_quota, enabled, version, updated_at, updated_by, approved_by)"
                + " values (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?) on conflict (client_id) do nothing",
            c.clientId(), s.type().name(), s.authMethod().name(), json.writeValueAsString(s.allowedRoutes()),
            json.writeValueAsString(s.ipAllowList()), s.ratePerSec(), s.dailyQuota(), s.isEnabled(), c.version(),
            Timestamp.from(c.updatedAt()), c.updatedBy(), c.approvedBy())
        == 1;
  }

  /** @return false when the client is no longer at {@code expectedVersion} */
  public boolean update(ChannelClient c, long expectedVersion) {
    ChannelClientSpec s = c.spec();
    return jdbc.update(
            "update channel_client set client_type = ?, auth_method = ?, allowed_routes = ?::jsonb,"
                + " ip_allow_list = ?::jsonb, rate_per_sec = ?, daily_quota = ?, enabled = ?, version = ?,"
                + " updated_at = ?, updated_by = ?, approved_by = ? where client_id = ? and version = ?",
            s.type().name(), s.authMethod().name(), json.writeValueAsString(s.allowedRoutes()),
            json.writeValueAsString(s.ipAllowList()), s.ratePerSec(), s.dailyQuota(), s.isEnabled(), c.version(),
            Timestamp.from(c.updatedAt()), c.updatedBy(), c.approvedBy(), c.clientId(), expectedVersion)
        == 1;
  }

  private ChannelClient row(ResultSet rs, int i) throws SQLException {
    long rate = rs.getLong("rate_per_sec");
    Long ratePerSec = rs.wasNull() ? null : rate;
    long quota = rs.getLong("daily_quota");
    Long dailyQuota = rs.wasNull() ? null : quota;
    return new ChannelClient(
        rs.getString("client_id"),
        new ChannelClientSpec(
            ChannelClientSpec.ClientType.valueOf(rs.getString("client_type")),
            ChannelClientSpec.AuthMethod.valueOf(rs.getString("auth_method")),
            json.readValue(rs.getString("allowed_routes"), LIST),
            json.readValue(rs.getString("ip_allow_list"), LIST),
            ratePerSec, dailyQuota, rs.getBoolean("enabled")),
        rs.getLong("version"),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("updated_by"),
        rs.getString("approved_by"));
  }
}
