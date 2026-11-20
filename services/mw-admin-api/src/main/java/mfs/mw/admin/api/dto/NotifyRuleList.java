package mfs.mw.admin.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /admin/v1/notify-rules} (ticket 133c, FRS NOT-01): the live (latest PUBLISHED) version
 * of every rule made in the console, by rule id. The seeded rules live in mw-notify.
 */
public record NotifyRuleList(List<Item> items) {

  public record Item(
      String ruleId,
      int version,
      String eventType,
      String outcome,
      String customerType,
      String recipient,
      List<String> channels,
      String templateCode,
      boolean enabled,
      String approvedBy,
      Instant approvedAt) {}
}
