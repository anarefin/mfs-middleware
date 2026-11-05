package mfs.mw.notify.service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.notify.dto.ConfigChangedEvent;
import mfs.mw.notify.repository.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes a notify template version the admin console published (ticket 39, spec 10.3, 20.4) from
 * {@code mw.config.changed.v1} (configType TEMPLATE, value {@code {code, channel, locale, version,
 * body, subject?, htmlBody?, senderId?, validFrom}}) and stores it as the next ACTIVE version of
 * {@code notify.template}, valid from {@code validFrom}. Rendering always takes the highest ACTIVE
 * version valid now, so the one insert swaps the template atomically: every message rendered from
 * then on uses it, none is rendered half-old.
 *
 * <ul>
 *   <li>The admin version is kept as {@code config_version}: a redelivered or replayed record is
 *       stored once, an older one ignored.
 *   <li>A value this service could not render (no body, an EMAIL without subject, a placeholder
 *       that is not a plain {@code {{name}}}) is logged ({@code notify.template.rejected}) and
 *       dropped, never retried.
 *   <li>Other config types, DELETE and tombstones are ignored.
 * </ul>
 */
@Service
public class TemplatePublisher {

  private static final Logger log = LoggerFactory.getLogger(TemplatePublisher.class);

  private static final Set<String> CHANNELS = Set.of("SMS", "EMAIL");
  private static final Pattern TAG = Pattern.compile("\\{\\{(.*?)}}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  private final TemplateRepository templates;
  private final TransactionTemplate tx;

  public TemplatePublisher(TemplateRepository templates, TransactionTemplate tx) {
    this.templates = templates;
    this.tx = tx;
  }

  public void onConfigChanged(ConfigChangedEvent e) {
    if (e == null
        || e.configType() != ConfigChangedEvent.ConfigType.TEMPLATE
        || e.change() != ConfigChangedEvent.Change.UPSERT
        || e.value() == null) {
      return;
    }
    Map<String, Object> v = e.value();
    String code = text(v, "code");
    String channel = text(v, "channel");
    String locale = text(v, "locale");
    String body = text(v, "body");
    String subject = text(v, "subject");
    String problem = problem(code, channel, locale, body, subject, text(v, "htmlBody"));
    if (problem != null) {
      log.atError()
          .addKeyValue("event", "notify.template.rejected")
          .addKeyValue("configKey", e.configKey())
          .addKeyValue("configVersion", e.version())
          .log("published template not usable, dropped: {}", problem);
      return;
    }
    Instant validFrom = v.get("validFrom") == null ? e.changedAt() : Instant.parse(String.valueOf(v.get("validFrom")));
    tx.executeWithoutResult(
        s -> {
          Long latest = templates.latestConfigVersion(code, channel, locale).orElse(null);
          if (latest != null && latest >= e.version()) {
            log.atInfo()
                .addKeyValue("event", "notify.template.publish_skipped")
                .addKeyValue("templateCode", code)
                .addKeyValue("configVersion", e.version())
                .log("template version already stored or older than the stored one");
            return;
          }
          int version =
              templates.insertPublished(
                  code, channel, locale, e.version(), subject, body, text(v, "htmlBody"), text(v, "senderId"), validFrom);
          log.atInfo()
              .addKeyValue("event", "notify.template.published")
              .addKeyValue("templateCode", code)
              .addKeyValue("channel", channel)
              .addKeyValue("locale", locale)
              .addKeyValue("templateVersion", version)
              .addKeyValue("configVersion", e.version())
              .addKeyValue("validFrom", validFrom.toString())
              .log("published template version stored");
        });
  }

  private static String problem(String code, String channel, String locale, String body, String subject, String html) {
    if (code == null || locale == null || !CHANNELS.contains(channel)) {
      return "code, channel (SMS|EMAIL) and locale are required";
    }
    if (body == null || body.isBlank()) {
      return "body is required";
    }
    if ("EMAIL".equals(channel) && (subject == null || subject.isBlank())) {
      return "an EMAIL template needs a subject";
    }
    for (String t : new String[] {body, subject, html}) {
      if (t != null && !plain(t)) {
        return "only plain {{name}} placeholders are rendered";
      }
    }
    return null;
  }

  private static boolean plain(String text) {
    Matcher m = TAG.matcher(text);
    while (m.find()) {
      if (!NAME.matcher(m.group(1)).matches()) {
        return false;
      }
    }
    return true;
  }

  private static String text(Map<String, Object> v, String key) {
    Object o = v.get(key);
    return o == null ? null : String.valueOf(o);
  }
}
