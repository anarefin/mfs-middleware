package mfs.mw.admin.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.admin.api.dto.PartnerConfigValue;
import mfs.mw.admin.api.entity.Partner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Schema {@code partner} (spec 16.2, own schema of mw-admin-api; ticket 38), plain JDBC: {@code
 * partner}, {@code endpoint}, {@code mapping}, {@code error_map}, read and written as one {@link
 * Partner}. A write replaces the partner's child rows.
 */
@Repository
public class PartnerRepository {

  private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
  private static final TypeReference<List<Integer>> INTS = new TypeReference<>() {};

  private static final String SELECT =
      "select p.id, p.family, p.enabled, p.template, p.connect_timeout_ms, p.timeout_ms, p.read_timeout_ms,"
          + " p.ambiguous_codes::text as ambiguous_codes, p.sensitive_fields::text as sensitive_fields, p.resilience,"
          + " p.version, p.updated_at, p.updated_by, p.approved_by, e.base_url, e.headers::text as headers,"
          + " e.auth_scheme, e.credentials_ref, e.auth_params::text as auth_params"
          + " from partner.partner p left join partner.endpoint e on e.partner_id = p.id";

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public PartnerRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<Partner> find(String id) {
    return jdbc.query(SELECT + " where p.id = ?", this::row, id).stream().findFirst().map(this::withChildren);
  }

  /** The partner row locked for the approving transaction. */
  public Optional<Partner> findForUpdate(String id) {
    List<String> locked = jdbc.queryForList("select id from partner.partner where id = ? for update", String.class, id);
    return locked.isEmpty() ? Optional.empty() : find(id);
  }

  public List<Partner> list() {
    return jdbc.query(SELECT + " order by p.id", this::row).stream().map(this::withChildren).toList();
  }

  /** Inserts a partner that does not exist yet (seed or first approved UPSERT). */
  public void insert(Partner p) {
    PartnerConfigValue c = p.config();
    jdbc.update(
        "insert into partner.partner (id, family, enabled, template, connect_timeout_ms, timeout_ms, read_timeout_ms,"
            + " ambiguous_codes, sensitive_fields, resilience, version, updated_at, updated_by, approved_by)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)",
        p.id(), p.family(), c.isEnabled(), c.template(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(),
        jsonOrNull(c.ambiguousCodes()), jsonOrNull(c.sensitiveFields()), c.resilience(), p.version(),
        Timestamp.from(p.updatedAt()), p.updatedBy(), p.approvedBy());
    insertChildren(p);
  }

  /**
   * Replaces the partner's config at {@code expectedVersion}.
   *
   * @return false when the partner is no longer at that version
   */
  public boolean update(Partner p, long expectedVersion) {
    PartnerConfigValue c = p.config();
    int n =
        jdbc.update(
            "update partner.partner set enabled = ?, template = ?, connect_timeout_ms = ?, timeout_ms = ?,"
                + " read_timeout_ms = ?, ambiguous_codes = ?::jsonb, sensitive_fields = ?::jsonb, resilience = ?,"
                + " version = ?, updated_at = ?, updated_by = ?, approved_by = ? where id = ? and version = ?",
            c.isEnabled(), c.template(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(),
            jsonOrNull(c.ambiguousCodes()), jsonOrNull(c.sensitiveFields()), c.resilience(), p.version(),
            Timestamp.from(p.updatedAt()), p.updatedBy(), p.approvedBy(), p.id(), expectedVersion);
    if (n != 1) {
      return false;
    }
    jdbc.update("delete from partner.endpoint where partner_id = ?", p.id());
    jdbc.update("delete from partner.mapping where partner_id = ?", p.id());
    jdbc.update("delete from partner.error_map where partner_id = ?", p.id());
    insertChildren(p);
    return true;
  }

  private void insertChildren(Partner p) {
    PartnerConfigValue c = p.config();
    PartnerConfigValue.Auth auth = c.auth();
    jdbc.update(
        "insert into partner.endpoint (partner_id, base_url, headers, auth_scheme, credentials_ref, auth_params)"
            + " values (?, ?, ?::jsonb, ?, ?, ?::jsonb)",
        p.id(), c.baseUrl(), jsonOrNull(c.headers()), auth == null ? null : auth.scheme(),
        auth == null ? null : auth.credentialsRef(), auth == null ? null : jsonOrNull(auth.params()));
    if (c.operations() != null) {
      c.operations().forEach(
          (name, op) ->
              jdbc.update(
                  "insert into partner.mapping (partner_id, operation, method, path, definite_statuses, request, response)"
                      + " values (?, ?, ?, ?, ?::jsonb, ?, ?)",
                  p.id(), name, op.method(), op.path(), jsonOrNull(op.definiteStatuses()), op.request(), op.response()));
    }
    if (c.errorMap() != null) {
      c.errorMap().forEach(
          (code, m) ->
              jdbc.update(
                  "insert into partner.error_map (partner_id, partner_code, suffix, retryable) values (?, ?, ?, ?)",
                  p.id(), code, m.suffix(), m.retryable()));
    }
  }

  private Partner withChildren(Partner p) {
    Map<String, PartnerConfigValue.Operation> ops = new LinkedHashMap<>();
    jdbc.query(
        "select operation, method, path, definite_statuses::text as definite_statuses, request, response"
            + " from partner.mapping where partner_id = ? order by operation",
        rs -> {
          String statuses = rs.getString("definite_statuses");
          ops.put(
              rs.getString("operation"),
              new PartnerConfigValue.Operation(
                  rs.getString("method"), rs.getString("path"), statuses == null ? null : json.readValue(statuses, INTS),
                  rs.getString("request"), rs.getString("response")));
        },
        p.id());
    Map<String, PartnerConfigValue.ErrorMapping> errors = new LinkedHashMap<>();
    jdbc.query(
        "select partner_code, suffix, retryable from partner.error_map where partner_id = ? order by partner_code",
        rs -> {
          errors.put(
              rs.getString("partner_code"),
              new PartnerConfigValue.ErrorMapping(rs.getString("suffix"), rs.getBoolean("retryable")));
        },
        p.id());
    PartnerConfigValue c = p.config();
    return new Partner(
        p.id(), p.family(), p.version(),
        new PartnerConfigValue(
            c.enabled(), c.template(), c.baseUrl(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(), c.headers(),
            c.auth(), ops.isEmpty() ? null : ops, errors.isEmpty() ? null : errors, c.ambiguousCodes(),
            c.sensitiveFields(), c.resilience()),
        p.updatedAt(), p.updatedBy(), p.approvedBy());
  }

  private Partner row(ResultSet rs, int i) throws SQLException {
    String scheme = rs.getString("auth_scheme");
    String ref = rs.getString("credentials_ref");
    Map<String, String> params = read(rs.getString("auth_params"), STRINGS);
    PartnerConfigValue.Auth auth =
        scheme == null && ref == null && params == null ? null : new PartnerConfigValue.Auth(scheme, ref, params);
    PartnerConfigValue config =
        new PartnerConfigValue(
            rs.getBoolean("enabled"), rs.getString("template"), rs.getString("base_url"),
            longOrNull(rs, "connect_timeout_ms"), longOrNull(rs, "timeout_ms"), longOrNull(rs, "read_timeout_ms"),
            read(rs.getString("headers"), STRINGS), auth, null, null, read(rs.getString("ambiguous_codes"), LIST),
            read(rs.getString("sensitive_fields"), STRINGS), rs.getString("resilience"));
    return new Partner(
        rs.getString("id"), rs.getString("family"), rs.getLong("version"), config,
        rs.getTimestamp("updated_at").toInstant(), rs.getString("updated_by"), rs.getString("approved_by"));
  }

  private <T> T read(String text, TypeReference<T> type) {
    return text == null ? null : json.readValue(text, type);
  }

  private String jsonOrNull(Object value) {
    return value == null ? null : json.writeValueAsString(value);
  }

  private static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }
}
