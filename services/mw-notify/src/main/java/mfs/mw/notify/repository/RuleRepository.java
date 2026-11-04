package mfs.mw.notify.repository;

import java.util.List;
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
        "select rule_id, source_topic, condition, template_code, recipient, channel, priority from rule"
            + " where enabled and source_topic = ? order by sort_order, rule_id",
        (rs, i) -> new RuleRecord(rs.getString("rule_id"), rs.getString("source_topic"), rs.getString("condition"),
            rs.getString("template_code"), rs.getString("recipient"), rs.getString("channel"),
            rs.getString("priority")),
        topic);
  }
}
