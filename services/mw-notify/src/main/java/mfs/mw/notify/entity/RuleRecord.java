package mfs.mw.notify.entity;

/** A row of {@code notify.rule} (spec 10.3): event condition to template and recipient. */
public record RuleRecord(
    String ruleId,
    String sourceTopic,
    String condition,
    String templateCode,
    String recipient,
    String channel,
    String priority) {}
