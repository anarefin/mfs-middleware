package mfs.mw.core.mock.repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.core.mock.entity.LimitRule;
import mfs.mw.core.mock.entity.TariffRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The tariff and limit sheets (tables {@code tariff} and {@code limit_rule}, ticket 94). */
@Repository
public class SheetRepository {

  private static final TypeReference<LinkedHashMap<String, BigDecimal>> SPLIT = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public SheetRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** The tariff rows of one txnType. */
  public List<TariffRow> tariff(String txnType) {
    return jdbc.query(
        "select * from tariff where txn_type = ? order by channel, customer_type, slab_min",
        (rs, i) -> new TariffRow(rs.getString("txn_type"), rs.getString("channel"), rs.getString("customer_type"),
            rs.getLong("slab_min"), rs.getLong("slab_max"), rs.getLong("fee"), rs.getBigDecimal("vat_pct"),
            json.readValue(rs.getString("commission_split"), SPLIT)),
        txnType);
  }

  /** The limit rules that may apply to {@code txnType} (its own and the {@code *} ones). */
  public List<LimitRule> limits(String txnType) {
    return jdbc.query(
        "select * from limit_rule where txn_type in (?, '*') order by txn_type, customer_type, kyc_level",
        (rs, i) -> new LimitRule(rs.getString("txn_type"), rs.getString("customer_type"), rs.getString("kyc_level"),
            rs.getObject("per_txn_min", Long.class), rs.getObject("per_txn_max", Long.class),
            rs.getObject("daily_count", Integer.class), rs.getObject("daily_amount", Long.class),
            rs.getObject("monthly_count", Integer.class), rs.getObject("monthly_amount", Long.class)),
        txnType);
  }

  /** Replaces the tariff sheet with {@code rows} (the seed file is its truth). */
  public void replaceTariff(List<TariffRow> rows) {
    jdbc.update("delete from tariff");
    jdbc.batchUpdate(
        "insert into tariff (txn_type, channel, customer_type, slab_min, slab_max, fee, vat_pct, commission_split) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        rows.stream().map(r -> new Object[] {r.txnType(), r.channel(), r.customerType(), r.slabMin(), r.slabMax(),
            r.feeMinor(), r.vatPct(), json.writeValueAsString(new LinkedHashMap<>(r.commissionSplit()))}).toList());
  }

  /** Replaces the limit sheet with {@code rules}. */
  public void replaceLimits(List<LimitRule> rules) {
    jdbc.update("delete from limit_rule");
    jdbc.batchUpdate(
        "insert into limit_rule (txn_type, customer_type, kyc_level, per_txn_min, per_txn_max, daily_count, "
            + "daily_amount, monthly_count, monthly_amount) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rules.stream().map(r -> new Object[] {r.txnType(), r.customerType(), r.kycLevel(), r.perTxnMin(), r.perTxnMax(),
            r.dailyCount(), r.dailyAmount(), r.monthlyCount(), r.monthlyAmount()}).toList());
  }

  /** Row counts (tests, logs). */
  public Map<String, Integer> counts() {
    return Map.of(
        "tariff", jdbc.queryForObject("select count(*) from tariff", Integer.class),
        "limit_rule", jdbc.queryForObject("select count(*) from limit_rule", Integer.class));
  }
}
