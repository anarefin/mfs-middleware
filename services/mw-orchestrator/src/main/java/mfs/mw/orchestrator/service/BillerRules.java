package mfs.mw.orchestrator.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.orchestrator.dto.ConfigChangedEvent;
import mfs.mw.orchestrator.repository.BillerRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * This service's projection of the biller catalogue (ticket 113c, FRS FL-04/FL-05): mw-admin-api
 * owns the catalogue and publishes each entry on {@code mw.config.changed.v1} (BILLER, key {@code
 * biller/<code>}, full value). Only the amount rule and its bounds are kept, in {@code
 * txn.biller_rule}, so they survive a restart like the routing tables. An older version is
 * ignored; a value without a known amount rule is skipped (logged), never retried.
 */
@Service
public class BillerRules {

  private static final Logger log = LoggerFactory.getLogger(BillerRules.class);

  static final List<String> RULES = List.of("FIXED", "MINIMUM", "OPEN");

  /** One biller's amount rule: FIXED, MINIMUM or OPEN, bounds in poisha (each optional). */
  public record Rule(String code, String amountRule, Long minAmountMinor, Long maxAmountMinor, long version) {}

  private final BillerRuleRepository repository;

  public BillerRules(BillerRuleRepository repository) {
    this.repository = repository;
  }

  public Optional<Rule> find(String billerCode) {
    return billerCode == null || billerCode.isBlank() ? Optional.empty() : repository.find(billerCode);
  }

  /** Applies a {@code mw.config.changed.v1} record; anything but BILLER is ignored. */
  public void apply(ConfigChangedEvent event) {
    if (event == null || event.configType() != ConfigChangedEvent.ConfigType.BILLER || event.configKey() == null) {
      return;
    }
    String code = event.configKey().substring(event.configKey().indexOf('/') + 1);
    if (code.isBlank()) {
      return;
    }
    if (event.change() == ConfigChangedEvent.Change.DELETE) {
      repository.delete(code, event.version());
      return;
    }
    Rule rule = parse(code, event.version(), event.value());
    if (rule == null) {
      log.atWarn()
          .addKeyValue("event", "biller.config_refused")
          .addKeyValue("billerCode", code)
          .log("biller amount rule skipped");
      return;
    }
    repository.upsert(rule);
    log.atInfo()
        .addKeyValue("event", "biller.config_applied")
        .addKeyValue("billerCode", code)
        .addKeyValue("version", event.version())
        .log("biller amount rule applied");
  }

  private static Rule parse(String code, long version, Map<String, Object> value) {
    if (value == null || !(value.get("amountRule") instanceof String rule) || !RULES.contains(rule)) {
      return null;
    }
    return new Rule(code, rule, minor(value.get("minAmountMinor")), minor(value.get("maxAmountMinor")), version);
  }

  private static Long minor(Object raw) {
    return raw instanceof Number n ? n.longValue() : null;
  }
}
