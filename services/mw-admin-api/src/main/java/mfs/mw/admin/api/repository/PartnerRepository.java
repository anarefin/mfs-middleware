package mfs.mw.admin.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
 * partner}, {@code endpoint}, {@code error_map} and the route rows {@code partner_route} (one per
 * version, ticket 83; the seed's as version 0, ticket 84), read and written as one {@link Partner}.
 * A write replaces the partner's child rows.
 */
@Repository
public class PartnerRepository {

  private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
  private static final TypeReference<PartnerConfigValue.DryRun> DRY_RUN = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> VALUE = new TypeReference<>() {};
  private static final TypeReference<PartnerConfigValue.MaintenanceWindow> WINDOW = new TypeReference<>() {};

  private static final String SELECT =
      "select p.id, p.family, p.enabled, p.connect_timeout_ms, p.timeout_ms, p.read_timeout_ms,"
          + " p.ambiguous_codes::text as ambiguous_codes, p.sensitive_fields::text as sensitive_fields, p.resilience,"
          + " p.maintenance, p.health_check, p.maintenance_window::text as maintenance_window,"
          + " e.endpoint_profile, e.endpoints::text as endpoints,"
          + " p.version, p.updated_at, p.updated_by, p.approved_by, e.headers::text as headers,"
          + " e.auth_scheme, e.credentials_ref, e.auth_params::text as auth_params"
          + " from partner.partner p left join partner.endpoint e on e.partner_id = p.id";

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public PartnerRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<Partner> find(String id) {
    return jdbc.query(SELECT + " where p.id = ?", this::row, id).stream().findFirst().map(this::withChildren).map(this::withRoute);
  }

  /** The partner row locked for the approving transaction. */
  public Optional<Partner> findForUpdate(String id) {
    List<String> locked = jdbc.queryForList("select id from partner.partner where id = ? for update", String.class, id);
    return locked.isEmpty() ? Optional.empty() : find(id);
  }

  public List<Partner> list() {
    return jdbc.query(SELECT + " order by p.id", this::row).stream().map(this::withChildren).map(this::withRoute).toList();
  }

  /** Inserts a partner that does not exist yet (seed or first approved UPSERT). */
  public void insert(Partner p) {
    PartnerConfigValue c = p.config();
    jdbc.update(
        "insert into partner.partner (id, family, enabled, connect_timeout_ms, timeout_ms, read_timeout_ms,"
            + " ambiguous_codes, sensitive_fields, resilience, maintenance, health_check, maintenance_window, version,"
            + " updated_at, updated_by, approved_by)"
            + " values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
        p.id(), p.family(), c.isEnabled(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(),
        jsonOrNull(c.ambiguousCodes()), jsonOrNull(c.sensitiveFields()), c.resilience(), c.maintenance(),
        c.healthCheck(), jsonOrNull(c.maintenanceWindow()), p.version(),
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
            "update partner.partner set enabled = ?, connect_timeout_ms = ?, timeout_ms = ?,"
                + " read_timeout_ms = ?, ambiguous_codes = ?::jsonb, sensitive_fields = ?::jsonb, resilience = ?,"
                + " maintenance = ?, health_check = ?, maintenance_window = ?::jsonb,"
                + " version = ?, updated_at = ?, updated_by = ?, approved_by = ? where id = ? and version = ?",
            c.isEnabled(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(),
            jsonOrNull(c.ambiguousCodes()), jsonOrNull(c.sensitiveFields()), c.resilience(), c.maintenance(),
            c.healthCheck(), jsonOrNull(c.maintenanceWindow()), p.version(),
            Timestamp.from(p.updatedAt()), p.updatedBy(), p.approvedBy(), p.id(), expectedVersion);
    if (n != 1) {
      return false;
    }
    jdbc.update("delete from partner.endpoint where partner_id = ?", p.id());
    jdbc.update("delete from partner.error_map where partner_id = ?", p.id());
    insertChildren(p);
    return true;
  }

  // --- partner routes (ticket 83) ---------------------------------------------------------------

  /**
   * Writes the route row of version {@code p.version()}, PENDING_DRY_RUN, in the approving
   * transaction, with the full value published for it (a later rollback republishes it).
   */
  public void insertRoute(Partner p, Map<String, Object> publishedValue, Instant at) {
    insertRoute(p, "PENDING_DRY_RUN", json.writeValueAsString(publishedValue), at);
  }

  /** Writes a seed's route row (version 0, SEED: never published, never reported). */
  public void insertSeedRoute(Partner p, Instant at) {
    insertRoute(p, "SEED", null, at);
  }

  private void insertRoute(Partner p, String status, String publishedValue, Instant at) {
    PartnerConfigValue c = p.config();
    jdbc.update(
        "insert into partner.partner_route (partner_id, version, family, route_yaml, mappings, allowed_hosts, dry_run,"
            + " route_status, published_value, created_at) values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?)",
        p.id(), p.version(), p.family(), c.route(), json.writeValueAsString(c.mappings() == null ? Map.of() : c.mappings()),
        json.writeValueAsString(c.allowedHosts() == null ? List.of() : c.allowedHosts()),
        json.writeValueAsString(c.dryRun()), status, publishedValue, Timestamp.from(at));
  }

  /**
   * The full value published for the newest version below {@code version} that its adapter
   * reported LIVE (ticket 84 rollback); empty when there is none or it predates the stored values.
   */
  public Optional<LivePublished> lastLiveBefore(String partnerId, long version) {
    return jdbc.query(
            "select version, published_value::text as published_value from partner.partner_route"
                + " where partner_id = ? and version < ? and route_status = 'LIVE' order by version desc limit 1",
            (rs, i) -> {
              String value = rs.getString("published_value");
              return value == null ? null : new LivePublished(rs.getLong("version"), read(value, VALUE));
            },
            partnerId, version)
        .stream().filter(java.util.Objects::nonNull).findFirst();
  }

  /** A LIVE version and the full value that was published for it. */
  public record LivePublished(long version, Map<String, Object> value) {}

  /**
   * Records what the adapter reported for one route version.
   *
   * @return false when no such version was approved here
   */
  public boolean updateRouteStatus(
      String partnerId, long version, String status, List<String> problems, List<String> kamelets, Instant at) {
    return jdbc.update(
            "update partner.partner_route set route_status = ?, problems = ?::jsonb, kamelets = ?::jsonb, status_at = ?"
                + " where partner_id = ? and version = ?",
            status, json.writeValueAsString(problems), json.writeValueAsString(kamelets), Timestamp.from(at), partnerId,
            version)
        == 1;
  }

  /** For each partner, its newest route version reported LIVE, with its Kamelets. */
  public List<LiveRoute> liveRoutes() {
    return jdbc.query(
        "select distinct on (partner_id) partner_id, version, kamelets::text as kamelets from partner.partner_route"
            + " where route_status = 'LIVE' order by partner_id, version desc",
        (rs, i) -> new LiveRoute(rs.getString("partner_id"), rs.getLong("version"), read(rs.getString("kamelets"), LIST)));
  }

  /** A live route version and the Kamelets it uses. */
  public record LiveRoute(String partnerId, long version, List<String> kamelets) {}

  /** The partner with the route fields and state of its current version, when it has a route. */
  private Partner withRoute(Partner p) {
    List<Partner> out = jdbc.query(
        "select r.route_yaml, r.mappings::text as mappings, r.allowed_hosts::text as allowed_hosts,"
            + " r.dry_run::text as dry_run, r.route_status, r.problems::text as problems, r.kamelets::text as kamelets,"
            + " r.status_at, (select max(l.version) from partner.partner_route l where l.partner_id = r.partner_id"
            + " and l.route_status = 'LIVE') as live_version"
            + " from partner.partner_route r where r.partner_id = ? and r.version = ?",
        (rs, i) -> {
          Timestamp statusAt = rs.getTimestamp("status_at");
          long live = rs.getLong("live_version");
          Long liveVersion = rs.wasNull() ? null : live;
          PartnerConfigValue c = p.config().withRoute(
              rs.getString("route_yaml"), read(rs.getString("mappings"), STRINGS), read(rs.getString("allowed_hosts"), LIST),
              read(rs.getString("dry_run"), DRY_RUN));
          if ("SEED".equals(rs.getString("route_status"))) {
            // a seed has a route but no approved version to report on
            return new Partner(p.id(), p.family(), p.version(), c, p.updatedAt(), p.updatedBy(), p.approvedBy());
          }
          return new Partner(
              p.id(), p.family(), p.version(), c, p.updatedAt(), p.updatedBy(), p.approvedBy(),
              new mfs.mw.admin.api.dto.PartnerView.Route(
                  p.version(), rs.getString("route_status"), read(rs.getString("problems"), LIST),
                  read(rs.getString("kamelets"), LIST), statusAt == null ? null : statusAt.toInstant(), liveVersion));
        },
        p.id(), p.version());
    return out.isEmpty() ? p : out.getFirst();
  }

  private void insertChildren(Partner p) {
    PartnerConfigValue c = p.config();
    PartnerConfigValue.Auth auth = c.auth();
    jdbc.update(
        "insert into partner.endpoint (partner_id, headers, auth_scheme, credentials_ref, auth_params, endpoint_profile,"
            + " endpoints) values (?, ?::jsonb, ?, ?, ?::jsonb, ?, ?::jsonb)",
        p.id(), jsonOrNull(c.headers()), auth == null ? null : auth.scheme(),
        auth == null ? null : auth.credentialsRef(), auth == null ? null : jsonOrNull(auth.params()), c.endpoint(),
        jsonOrNull(c.endpoints()));
    if (c.errorMap() != null) {
      c.errorMap().forEach(
          (code, m) ->
              jdbc.update(
                  "insert into partner.error_map (partner_id, partner_code, suffix, retryable) values (?, ?, ?, ?)",
                  p.id(), code, m.suffix(), m.retryable()));
    }
  }

  private Partner withChildren(Partner p) {
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
            c.enabled(), c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs(), c.headers(), c.auth(),
            errors.isEmpty() ? null : errors, c.ambiguousCodes(), c.sensitiveFields(), c.resilience(), null, null, null,
            null, c.maintenance(), c.healthCheck(), c.maintenanceWindow(), c.endpoint(), c.endpoints()),
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
            rs.getBoolean("enabled"), longOrNull(rs, "connect_timeout_ms"), longOrNull(rs, "timeout_ms"),
            longOrNull(rs, "read_timeout_ms"), read(rs.getString("headers"), STRINGS), auth, null,
            read(rs.getString("ambiguous_codes"), LIST), read(rs.getString("sensitive_fields"), STRINGS),
            rs.getString("resilience"), null, null, null, null, booleanOrNull(rs, "maintenance"),
            booleanOrNull(rs, "health_check"), read(rs.getString("maintenance_window"), WINDOW),
            rs.getString("endpoint_profile"), read(rs.getString("endpoints"), STRINGS));
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

  private static Boolean booleanOrNull(ResultSet rs, String column) throws SQLException {
    boolean v = rs.getBoolean(column);
    return rs.wasNull() ? null : v;
  }

  private static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long v = rs.getLong(column);
    return rs.wasNull() ? null : v;
  }
}
