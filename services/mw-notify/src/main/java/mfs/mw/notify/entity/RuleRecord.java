package mfs.mw.notify.entity;

/**
 * A row of {@code notify.rule} (spec 10.3, FRS NOT-01): event condition, outcome (the condition's
 * status) and customer type to a template and recipient. {@code customerType} is CUSTOMER, AGENT,
 * MERCHANT, DISTRIBUTOR, DSO, ORG or {@code *} (any). ORG is stored even though current txn events
 * never emit it.
 */
public record RuleRecord(
    String ruleId,
    String sourceTopic,
    String condition,
    String templateCode,
    String recipient,
    String channel,
    String priority,
    String customerType) {

  /** A rule that matches every customer type ({@code *}). */
  public RuleRecord(String ruleId, String sourceTopic, String condition, String templateCode, String recipient,
      String channel, String priority) {
    this(ruleId, sourceTopic, condition, templateCode, recipient, channel, priority, "*");
  }

  public RuleRecord {
    if (customerType == null || customerType.isBlank()) {
      customerType = "*";
    }
  }
}
