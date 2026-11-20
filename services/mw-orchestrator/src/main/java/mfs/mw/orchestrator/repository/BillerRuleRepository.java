package mfs.mw.orchestrator.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import mfs.mw.orchestrator.service.BillerRules.Rule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code txn.biller_rule} (ticket 113c): the biller amount rules applied from {@code mw.config.changed.v1}. */
@Repository
public class BillerRuleRepository {

  private final JdbcTemplate jdbc;

  public BillerRuleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Rule> find(String code) {
    return jdbc.query(
            "select code, amount_rule, min_amount_minor, max_amount_minor, version from biller_rule where code = ?",
            (rs, i) ->
                new Rule(
                    rs.getString("code"),
                    rs.getString("amount_rule"),
                    boxed(rs, "min_amount_minor"),
                    boxed(rs, "max_amount_minor"),
                    rs.getLong("version")),
            code)
        .stream()
        .findFirst();
  }

  /** Inserts or replaces the rule; a version not newer than the one held is ignored. */
  public void upsert(Rule rule) {
    jdbc.update(
        "insert into biller_rule (code, amount_rule, min_amount_minor, max_amount_minor, version)"
            + " values (?, ?, ?, ?, ?)"
            + " on conflict (code) do update set amount_rule = excluded.amount_rule,"
            + " min_amount_minor = excluded.min_amount_minor, max_amount_minor = excluded.max_amount_minor,"
            + " version = excluded.version where biller_rule.version < excluded.version",
        rule.code(),
        rule.amountRule(),
        rule.minAmountMinor(),
        rule.maxAmountMinor(),
        rule.version());
  }

  public void delete(String code, long version) {
    jdbc.update("delete from biller_rule where code = ? and version <= ?", code, version);
  }

  private static Long boxed(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
