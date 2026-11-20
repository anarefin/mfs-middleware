package mfs.mw.admin.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The approved biller catalogue (ticket 113, table {@code biller} V16). A row is the contract's
 * {@code BillerValue} plus its version.
 */
@Repository
public class BillerRepository {

  /** One catalogue entry: {@code value} is the published {@code BillerValue}. */
  public record Row(String code, int version, Map<String, Object> value) {}

  private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public BillerRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private static final String COLUMNS =
      "code, name_en, name_bn, category, primary_aggregator, fallback_aggregator, fields::text,"
          + " amount_rule, min_amount_minor, max_amount_minor, bill_month_required, enabled,"
          + " maintenance_en, maintenance_bn, version";

  public Optional<Row> findForUpdate(String code) {
    return jdbc.query("select " + COLUMNS + " from biller where code = ? for update", this::row, code).stream()
        .findFirst();
  }

  public Optional<Row> find(String code) {
    return jdbc.query("select " + COLUMNS + " from biller where code = ?", this::row, code).stream().findFirst();
  }

  public List<Row> list() {
    return jdbc.query("select " + COLUMNS + " from biller order by category desc, code", this::row);
  }

  /** Inserts or replaces the entry; {@code value} is a checked {@code BillerValue}. */
  public void save(String code, int version, Map<String, Object> value, Instant at, String by) {
    jdbc.update(
        "insert into biller (code, name_en, name_bn, category, primary_aggregator, fallback_aggregator, fields,"
            + " amount_rule, min_amount_minor, max_amount_minor, bill_month_required, enabled, maintenance_en,"
            + " maintenance_bn, version, updated_at, updated_by)"
            + " values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " on conflict (code) do update set name_en = excluded.name_en, name_bn = excluded.name_bn,"
            + " category = excluded.category, primary_aggregator = excluded.primary_aggregator,"
            + " fallback_aggregator = excluded.fallback_aggregator, fields = excluded.fields,"
            + " amount_rule = excluded.amount_rule, min_amount_minor = excluded.min_amount_minor,"
            + " max_amount_minor = excluded.max_amount_minor, bill_month_required = excluded.bill_month_required,"
            + " enabled = excluded.enabled, maintenance_en = excluded.maintenance_en,"
            + " maintenance_bn = excluded.maintenance_bn, version = excluded.version,"
            + " updated_at = excluded.updated_at, updated_by = excluded.updated_by",
        code,
        value.get("nameEn"),
        value.get("nameBn"),
        value.get("category"),
        value.get("primaryAggregator"),
        value.get("fallbackAggregator"),
        json.writeValueAsString(value.get("fields")),
        value.get("amountRule"),
        number(value.get("minAmountMinor")),
        number(value.get("maxAmountMinor")),
        value.get("billMonthRequired"),
        value.get("enabled"),
        value.get("maintenanceEn"),
        value.get("maintenanceBn"),
        version,
        Timestamp.from(at),
        by);
  }

  /** Stores a seed entry at version 1 unless the code exists; true when stored. */
  public boolean insertIfAbsent(String code, Map<String, Object> value, Instant at) {
    if (jdbc.queryForObject("select count(*) from biller where code = ?", Integer.class, code) > 0) {
      return false;
    }
    save(code, 1, value, at, "seed");
    return true;
  }

  private static Long number(Object raw) {
    return raw instanceof Number n ? n.longValue() : null;
  }

  private Row row(ResultSet rs, int i) throws SQLException {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("code", rs.getString(1));
    v.put("nameEn", rs.getString(2));
    v.put("nameBn", rs.getString(3));
    v.put("category", rs.getString(4));
    v.put("primaryAggregator", rs.getString(5));
    put(v, "fallbackAggregator", rs.getString(6));
    v.put("fields", json.readValue(rs.getString(7), LIST));
    v.put("amountRule", rs.getString(8));
    long min = rs.getLong(9);
    if (!rs.wasNull()) {
      v.put("minAmountMinor", min);
    }
    long max = rs.getLong(10);
    if (!rs.wasNull()) {
      v.put("maxAmountMinor", max);
    }
    v.put("billMonthRequired", rs.getBoolean(11));
    v.put("enabled", rs.getBoolean(12));
    put(v, "maintenanceEn", rs.getString(13));
    put(v, "maintenanceBn", rs.getString(14));
    return new Row(rs.getString(1), rs.getInt(15), v);
  }

  private static void put(Map<String, Object> v, String key, Object value) {
    if (value != null) {
      v.put(key, value);
    }
  }
}
