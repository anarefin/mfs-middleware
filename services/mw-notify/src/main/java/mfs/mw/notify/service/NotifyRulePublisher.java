package mfs.mw.notify.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.ConfigChangedEvent;
import mfs.mw.notify.entity.RuleRecord;
import mfs.mw.notify.repository.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies a notification rule edited in the admin console (ticket 133c, FRS NOT-01) from {@code
 * mw.config.changed.v1} (configType NOTIFY_RULE, configKey {@code notify-rule/<ruleId>}, value
 * {@code {ruleId, eventType, outcome, customerType, recipient, channels, templateCode, enabled}})
 * to {@code notify.rule}, without a restart: the rules are read for every event, so the next
 * matching event uses the new channel set.
 *
 * <ul>
 *   <li>One row per channel, {@code admin-<ruleId>-<channel>}, condition {@code <outcome statuses>
 *       and txnType == '<eventType>'} on {@code mw.txn.status.v1} (SUCCESS: COMPLETED; FAILURE:
 *       FAILED, REJECTED; PROCESSING: UNKNOWN, MANUAL_REVIEW). A channel the version leaves out, a
 *       disabled rule and a DELETE disable the rows.
 *   <li>The admin version is kept as {@code config_version}: a redelivered or older record changes
 *       nothing.
 *   <li>A value that does not hold to the contract's patterns is logged ({@code
 *       notify.rule.rejected}) and dropped, never retried; the condition is only built from values
 *       that passed them.
 * </ul>
 */
@Service
public class NotifyRulePublisher {

  private static final Logger log = LoggerFactory.getLogger(NotifyRulePublisher.class);

  static final String KEY_PREFIX = "notify-rule/";
  static final String TOPIC = "mw.txn.status.v1";
  static final int SORT_ORDER = 500;

  private static final Pattern RULE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
  private static final Pattern EVENT_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
  private static final Map<String, String> OUTCOMES = Map.of(
      "SUCCESS", "status == 'COMPLETED'",
      "FAILURE", "(status == 'FAILED' or status == 'REJECTED')",
      "PROCESSING", "(status == 'UNKNOWN' or status == 'MANUAL_REVIEW')");
  private static final Set<String> CUSTOMER_TYPES =
      Set.of("CUSTOMER", "AGENT", "MERCHANT", "DISTRIBUTOR", "DSO", "ORG", "*");
  private static final Set<String> RECIPIENTS = Set.of("INITIATOR", "DEBIT_PARTY", "CREDIT_PARTY", "OPS");

  private final RuleRepository rules;
  private final TransactionTemplate tx;

  public NotifyRulePublisher(RuleRepository rules, TransactionTemplate tx) {
    this.rules = rules;
    this.tx = tx;
  }

  public void onConfigChanged(ConfigChangedEvent e) {
    if (e == null
        || e.configType() != ConfigChangedEvent.ConfigType.NOTIFY_RULE
        || e.configKey() == null
        || !e.configKey().startsWith(KEY_PREFIX)) {
      return;
    }
    String ruleId = e.configKey().substring(KEY_PREFIX.length());
    if (!RULE_ID.matcher(ruleId).matches()) {
      rejected(e, "configKey is not notify-rule/<ruleId>");
      return;
    }
    if (e.change() == ConfigChangedEvent.Change.DELETE) {
      apply(e, ruleId, null);
      return;
    }
    if (e.value() == null) {
      rejected(e, "an UPSERT needs a value");
      return;
    }
    Map<String, Object> v = e.value();
    String problem = problem(ruleId, v);
    if (problem != null) {
      rejected(e, problem);
      return;
    }
    apply(e, ruleId, v);
  }

  /** Stores the rule's rows; {@code v} null (DELETE) or {@code enabled false} disables them. */
  private void apply(ConfigChangedEvent e, String ruleId, Map<String, Object> v) {
    List<String> ids = new ArrayList<>();
    for (Channel c : Channel.values()) {
      ids.add(rowId(ruleId, c));
    }
    boolean enabled = v != null && Boolean.TRUE.equals(v.get("enabled"));
    List<?> channels = v == null ? List.of() : (List<?>) v.get("channels");
    tx.executeWithoutResult(
        s -> {
          Long latest = rules.latestConfigVersion(ids).orElse(null);
          if (latest != null && latest >= e.version()) {
            log.atInfo()
                .addKeyValue("event", "notify.rule.publish_skipped")
                .addKeyValue("ruleId", ruleId)
                .addKeyValue("configVersion", e.version())
                .log("rule version already applied or older than the applied one");
            return;
          }
          for (Channel c : Channel.values()) {
            if (enabled && channels.contains(c.name())) {
              rules.upsert(record(ruleId, c, v), true, SORT_ORDER, e.version());
            } else {
              rules.disable(rowId(ruleId, c), e.version());
            }
          }
          log.atInfo()
              .addKeyValue("event", "notify.rule.published")
              .addKeyValue("ruleId", ruleId)
              .addKeyValue("configVersion", e.version())
              .addKeyValue("enabled", enabled)
              .addKeyValue("channels", String.valueOf(enabled ? channels : List.of()))
              .log("notification rule applied");
        });
  }

  private static RuleRecord record(String ruleId, Channel channel, Map<String, Object> v) {
    String condition = OUTCOMES.get(text(v, "outcome")) + " and txnType == '" + text(v, "eventType") + "'";
    return new RuleRecord(rowId(ruleId, channel), TOPIC, condition, text(v, "templateCode"), text(v, "recipient"),
        channel.name(), "TXN", text(v, "customerType"));
  }

  static String rowId(String ruleId, Channel channel) {
    return "admin-" + ruleId + "-" + channel.name().toLowerCase(Locale.ROOT);
  }

  private static String problem(String ruleId, Map<String, Object> v) {
    if (v.get("ruleId") != null && !ruleId.equals(text(v, "ruleId"))) {
      return "ruleId differs from the configKey";
    }
    String eventType = text(v, "eventType");
    if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
      return "eventType must be a txnType";
    }
    if (!OUTCOMES.containsKey(text(v, "outcome"))) {
      return "outcome must be SUCCESS, FAILURE or PROCESSING";
    }
    if (!CUSTOMER_TYPES.contains(text(v, "customerType"))) {
      return "customerType must be a wallet type, ORG or *";
    }
    if (!RECIPIENTS.contains(text(v, "recipient"))) {
      return "recipient must be INITIATOR, DEBIT_PARTY, CREDIT_PARTY or OPS";
    }
    String code = text(v, "templateCode");
    if (code == null || !CODE.matcher(code).matches()) {
      return "templateCode is not a template code";
    }
    if (!(v.get("enabled") instanceof Boolean)) {
      return "enabled must be true or false";
    }
    if (!(v.get("channels") instanceof List<?> channels) || channels.isEmpty()) {
      return "channels must name SMS, EMAIL or PUSH";
    }
    for (Object c : channels) {
      if (!"SMS".equals(c) && !"EMAIL".equals(c) && !"PUSH".equals(c)) {
        return "channels must name SMS, EMAIL or PUSH";
      }
    }
    return null;
  }

  private static void rejected(ConfigChangedEvent e, String problem) {
    log.atError()
        .addKeyValue("event", "notify.rule.rejected")
        .addKeyValue("configKey", e.configKey())
        .addKeyValue("configVersion", e.version())
        .log("published notification rule not usable, dropped: {}", problem);
  }

  private static String text(Map<String, Object> v, String key) {
    Object o = v.get(key);
    return o == null ? null : String.valueOf(o);
  }
}
