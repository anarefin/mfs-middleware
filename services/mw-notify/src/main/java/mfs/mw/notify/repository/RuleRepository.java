package mfs.mw.notify.repository;

import java.util.List;
import java.util.Optional;
import mfs.mw.notify.entity.RuleRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code notify.rule} (spec 10.3). */
@Repository
public class RuleRepository {

  private final JdbcTemplate jdbc;

  public RuleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Enabled rules for events of {@code topic}, in evaluation order. */
  public List<RuleRecord> enabledFor(String topic) {
    return jdbc.query(
        "select rule_id, source_topic, condition, template_code, recipient, channel, priority, customer_type"
            + " from rule where enabled and source_topic = ? order by sort_order, rule_id",
        (rs, i) -> new RuleRecord(rs.getString("rule_id"), rs.getString("source_topic"), rs.getString("condition"),
            rs.getString("template_code"), rs.getString("recipient"), rs.getString("channel"),
            rs.getString("priority"), rs.getString("customer_type")),
        topic);
  }

  /**
   * The highest admin version stored in the rows {@code ruleIds} (ticket 133c), locking them for
   * the caller's transaction.
   */
  public Optional<Long> latestConfigVersion(List<String> ruleIds) {
    List<Long> versions = jdbc.queryForList(
        "select config_version from rule where rule_id = any (?) and config_version is not null for update",
        Long.class, (Object) ruleIds.toArray(String[]::new));
    return versions.stream().max(Long::compare);
  }

  /** Inserts or replaces the row {@code rule.ruleId()} with an admin version (ticket 133c). */
  public void upsert(RuleRecord rule, boolean enabled, int sortOrder, long configVersion) {
    jdbc.update(
        "insert into rule (rule_id, source_topic, condition, template_code, recipient, channel, priority,"
            + " customer_type, enabled, sort_order, config_version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " on conflict (rule_id) do update set source_topic = excluded.source_topic,"
            + " condition = excluded.condition, template_code = excluded.template_code,"
            + " recipient = excluded.recipient, channel = excluded.channel, priority = excluded.priority,"
            + " customer_type = excluded.customer_type, enabled = excluded.enabled,"
            + " sort_order = excluded.sort_order, config_version = excluded.config_version",
        rule.ruleId(), rule.sourceTopic(), rule.condition(), rule.templateCode(), rule.recipient(), rule.channel(),
        rule.priority(), rule.customerType(), enabled, sortOrder, configVersion);
  }

  /** Disables the row {@code ruleId}, if any, and stamps the admin version. */
  public void disable(String ruleId, long configVersion) {
    jdbc.update("update rule set enabled = false, config_version = ? where rule_id = ?", configVersion, ruleId);
  }
}
