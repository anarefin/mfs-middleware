package mfs.mw.notify.service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.notify.config.NotifyProperties;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.ConfigChangedEvent;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.repository.CampaignRepository;
import mfs.mw.notify.repository.CampaignRepository.Running;
import mfs.mw.notify.util.MsisdnHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled campaigns (ticket 111, NOT-07). An approved {@code FEATURE_FLAG} whose key is
 * {@code feature/notify-campaign/<id>} on {@code mw.config.changed.v1} is stored, then {@link #run}
 * queues at most {@code throttlePerSecond} recipients per second as PROMO SMS. The dispatcher sends
 * those on the PROMO bind after OTP, TXN and INFO, so a campaign never delays them. Quiet hours
 * hold the campaign the same way they hold any PROMO.
 */
@Service
public class CampaignService {

  private static final Logger log = LoggerFactory.getLogger(CampaignService.class);
  static final String KEY_PREFIX = "feature/notify-campaign/";

  private final CampaignRepository campaigns;
  private final NotificationService notifications;
  private final OptOutService optOuts;
  private final NotifyProperties props;
  private final Clock clock;

  public CampaignService(CampaignRepository campaigns, NotificationService notifications, OptOutService optOuts,
      NotifyProperties props, Clock clock) {
    this.campaigns = campaigns;
    this.notifications = notifications;
    this.optOuts = optOuts;
    this.props = props;
    this.clock = clock;
  }

  /** Stores an approved campaign. Other config types and other feature flags are ignored. */
  @Transactional
  public void onConfigChanged(ConfigChangedEvent e) {
    if (e == null
        || e.configType() != ConfigChangedEvent.ConfigType.FEATURE_FLAG
        || e.change() != ConfigChangedEvent.Change.UPSERT
        || e.configKey() == null
        || !e.configKey().startsWith(KEY_PREFIX)
        || e.value() == null) {
      return;
    }
    String id = e.configKey().substring(KEY_PREFIX.length());
    Map<String, Object> v = e.value();
    String template = text(v, "templateCode");
    String locale = text(v, "locale");
    String schedule = text(v, "scheduleAt");
    Object throttle = v.get("throttlePerSecond");
    if (template == null || locale == null || schedule == null || !(throttle instanceof Number n) || n.intValue() < 1) {
      log.atError().addKeyValue("event", "notify.campaign.rejected").addKeyValue("configKey", e.configKey())
          .log("published campaign not usable, dropped");
      return;
    }
    Instant at;
    try {
      at = Instant.parse(schedule);
    } catch (DateTimeParseException ex) {
      log.atError().addKeyValue("event", "notify.campaign.rejected").addKeyValue("configKey", e.configKey())
          .log("published campaign not usable, dropped");
      return;
    }
    List<String> msisdns = msisdns(v.get("msisdns"));
    boolean stored = campaigns.insertApproved(id, template, locale, text(v, "text"), text(v, "segmentQuery"),
        text(v, "customerType"), at, n.intValue(), e.version(), msisdns);
    if (stored) {
      log.atInfo().addKeyValue("event", "notify.campaign.approved").addKeyValue("campaignId", id)
          .addKeyValue("recipients", msisdns.size()).log("campaign approved");
    }
  }

  /**
   * One scheduler tick: start due campaigns and queue up to the remaining per-second budget of each.
   * Returns how many PROMO messages were queued. Does not sleep and does not submit to the SMSC.
   */
  @Transactional
  public int run() {
    Instant now = clock.instant();
    if (props.quietHours().contains(now)) {
      return 0;
    }
    campaigns.startDue(now);
    int queued = 0;
    for (Running c : campaigns.running()) {
      int room = c.throttlePerSecond() - campaigns.queuedSince(c.id(), now.minusSeconds(1));
      if (room <= 0) {
        continue;
      }
      for (String msisdn : campaigns.lockPending(c.id(), room)) {
        if (optOuts.blocksPromo(Priority.PROMO, msisdn)) {
          campaigns.mark(c.id(), msisdn, "SKIPPED", now);
          continue;
        }
        notifications.accept(request(c, msisdn));
        campaigns.mark(c.id(), msisdn, "QUEUED", now);
        queued++;
      }
      campaigns.finishIfDrained(c.id());
    }
    return queued;
  }

  private static NotificationRequest request(Running c, String msisdn) {
    Map<String, String> params = new LinkedHashMap<>();
    if (c.text() != null) {
      params.put("text", c.text());
    }
    return new NotificationRequest(1, "campaign:" + c.id() + ":" + msisdn, null, c.id(),
        new NotificationRequest.Recipient(msisdn, null, null, null), c.templateCode(), c.locale(), params,
        List.of(Channel.SMS), Priority.PROMO, null, null);
  }

  private static String text(Map<String, Object> v, String key) {
    Object o = v.get(key);
    if (o == null) {
      return null;
    }
    String s = String.valueOf(o);
    return s.isBlank() ? null : s;
  }

  private static List<String> msisdns(Object raw) {
    List<String> out = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        String canonical = MsisdnHash.canonical(item == null ? null : String.valueOf(item));
        if (canonical != null && !out.contains(canonical)) {
          out.add(canonical);
        }
      }
    }
    return out;
  }
}
