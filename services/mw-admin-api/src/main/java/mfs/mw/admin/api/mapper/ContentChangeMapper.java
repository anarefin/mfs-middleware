package mfs.mw.admin.api.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.ContentVersionView;
import mfs.mw.admin.api.dto.NotifyRuleList;
import mfs.mw.admin.api.entity.ContentVersion;

/**
 * USSD menu and notify template versions (ticket 39) to their keys, their {@code admin.v1} view and
 * the {@code mw.config.changed.v1} record an approval publishes.
 *
 * <ul>
 *   <li>USSD menu: resource {@code ussd_menu/<menuId>}, configKey {@code ussd-menu/<menuId>}, value
 *       {@code {menuId, version, menu, activations: [{mno, goLiveAt}]}}.
 *   <li>Template: resource {@code notify_template/<code>/<channel>/<locale>}, configKey {@code
 *       template/<code>/<channel>/<locale>}, value {@code {code, channel, locale, version, body,
 *       subject?, htmlBody?, senderId?, validFrom}}.
 *   <li>Notify rule (ticket 133c): resource {@code notify-rule/<ruleId>}, configKey {@code
 *       notify-rule/<ruleId>}, value {@code {ruleId, version, eventType, outcome, customerType,
 *       recipient, channels, templateCode, enabled}}.
 * </ul>
 */
public final class ContentChangeMapper {

  public static final String USSD_MENU = "ussd_menu";
  public static final String NOTIFY_TEMPLATE = "notify_template";
  public static final String NOTIFY_RULE = "notify-rule";
  public static final String NOTIFY_RULE_KEY_PREFIX = "notify-rule/";

  private ContentChangeMapper() {}

  public static String menuKey(String menuId) {
    return "ussd-menu/" + menuId;
  }

  public static String templateResource(String code, String channel, String locale) {
    return code + "/" + channel + "/" + locale;
  }

  public static String templateKey(String code, String channel, String locale) {
    return "template/" + templateResource(code, channel, locale);
  }

  public static String notifyRuleKey(String ruleId) {
    return NOTIFY_RULE_KEY_PREFIX + ruleId;
  }

  /** The configKey of a content change request's resource. */
  public static String configKey(String resourceType, String resourceId) {
    if (NOTIFY_RULE.equals(resourceType)) {
      return notifyRuleKey(resourceId);
    }
    return USSD_MENU.equals(resourceType) ? menuKey(resourceId) : "template/" + resourceId;
  }

  public static String configType(String resourceType) {
    if (NOTIFY_RULE.equals(resourceType)) {
      return ContentVersion.NOTIFY_RULE;
    }
    return USSD_MENU.equals(resourceType) ? ContentVersion.USSD_MENU : ContentVersion.TEMPLATE;
  }

  /** The live rule of a published version (ticket 133c). */
  @SuppressWarnings("unchecked")
  public static NotifyRuleList.Item notifyRule(ContentVersion v) {
    Map<String, Object> value = v.value();
    return new NotifyRuleList.Item(
        String.valueOf(value.get("ruleId")), v.version(), String.valueOf(value.get("eventType")),
        String.valueOf(value.get("outcome")), String.valueOf(value.get("customerType")),
        String.valueOf(value.get("recipient")), List.copyOf((List<String>) value.get("channels")),
        String.valueOf(value.get("templateCode")), Boolean.TRUE.equals(value.get("enabled")), v.checkerId(),
        v.decidedAt());
  }

  /** {@code diff}: the latest published version (absent for the first) and the new one. */
  public static Map<String, Object> diff(String configKey, Integer published, int version) {
    Map<String, Object> before = published == null ? Map.of("configKey", configKey) : Map.of("configKey", configKey, "version", published);
    return Map.of("before", before, "after", Map.of("configKey", configKey, "version", version));
  }

  public static ConfigChangedEvent published(
      ContentVersion v, Map<String, Object> value, String checkerId, String correlationId, Instant at) {
    return new ConfigChangedEvent(
        1, v.configKey(), ConfigChangedEvent.ConfigType.valueOf(v.configType()), ConfigChangedEvent.Change.UPSERT,
        v.version(), value, at, v.makerId(), checkerId, correlationId);
  }

  public static ContentVersionView view(ContentVersion v, boolean withValue) {
    return new ContentVersionView(
        v.configKey(), v.version(), v.status().name(), v.changeRequestId().toString(), v.makerId(), v.checkerId(),
        v.createdAt(), v.decidedAt(), withValue ? v.value() : null);
  }
}
