package mfs.mw.admin.api.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.NotifyCampaignBody;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.ChangeRequestMapper;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Promotional campaigns through maker-checker (ticket 111, NOT-07). There is no campaign table
 * here: the change request holds the audience, and approval writes {@code mw.config.changed.v1}
 * as a {@code FEATURE_FLAG} keyed {@code feature/notify-campaign/<id>} (the config type enum is
 * closed). mw-notify stores it and sends it on the PROMO bind at {@code throttlePerSecond}.
 */
@Service
public class NotifyCampaignService {

  private static final Logger log = LoggerFactory.getLogger(NotifyCampaignService.class);

  public static final String RESOURCE = "notify-campaign";
  public static final String TOPIC = "mw.config.changed.v1";
  public static final String KEY_PREFIX = "feature/notify-campaign/";

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern MSISDN = Pattern.compile("01[3-9]\\d{8}");
  private static final Set<String> LOCALES = Set.of("bn", "en");
  private static final Set<String> CUSTOMER_TYPES =
      Set.of("CUSTOMER", "AGENT", "MERCHANT", "DISTRIBUTOR", "DSO", "ORG");
  private static final int MAX_RECIPIENTS = 5000;

  private final ObjectProvider<ChangeRequestService> changeRequests;
  private final OutboxWriter outbox;

  public NotifyCampaignService(ObjectProvider<ChangeRequestService> changeRequests, OutboxWriter outbox) {
    this.changeRequests = changeRequests;
    this.outbox = outbox;
  }

  public ChangeRequestView propose(Operator maker, NotifyCampaignBody body) {
    if (!maker.hasAny(AdminRole.CONTENT_MAKER)) {
      throw new ForbiddenException("Proposing a campaign needs the ops-maker role");
    }
    Map<String, Object> payload = payload(body);
    String id = UUID.randomUUID().toString();
    return changeRequests.getObject().open(maker, RESOURCE, id, "CREATE", payload,
        ChangeRequestMapper.diff("NONE", "APPROVED"), body.reason().trim());
  }

  /** Approval: the campaign value on {@code mw.config.changed.v1}. The reason stays on the request. */
  public Map<String, Object> publish(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    String key = KEY_PREFIX + cr.resourceId();
    ConfigChangedEvent event = new ConfigChangedEvent(1, key, ConfigChangedEvent.ConfigType.FEATURE_FLAG,
        ConfigChangedEvent.Change.UPSERT, 1, cr.payload(), now, cr.makerId(), checker.username(), correlationId);
    outbox.append(TOPIC, key,
        new EventMetadata(correlationId, cr.id().toString(), cr.id() + ":1", 1), event);
    log.atInfo().addKeyValue("event", "admin.campaign.published").addKeyValue("configKey", key)
        .log("campaign published");
    return Map.of("configKey", key, "version", 1);
  }

  private static Map<String, Object> payload(NotifyCampaignBody body) {
    List<ErrorDetail> problems = new ArrayList<>();
    String template = blank(body.templateCode()) ? "CAMPAIGN_PROMO" : body.templateCode().trim();
    if (!CODE.matcher(template).matches()) {
      problems.add(new ErrorDetail("templateCode", "must be 1-64 of A-Z, 0-9 or _, starting with a letter"));
    }
    String locale = blank(body.locale()) ? "bn" : body.locale().trim();
    if (!LOCALES.contains(locale)) {
      problems.add(new ErrorDetail("locale", "must be bn or en"));
    }
    if (blank(body.scheduleAt())) {
      problems.add(new ErrorDetail("scheduleAt", "is required"));
    } else {
      try {
        Instant.parse(body.scheduleAt().trim());
      } catch (DateTimeParseException e) {
        problems.add(new ErrorDetail("scheduleAt", "must be a UTC instant"));
      }
    }
    if (body.throttlePerSecond() == null || body.throttlePerSecond() < 1 || body.throttlePerSecond() > 1000) {
      problems.add(new ErrorDetail("throttlePerSecond", "must be from 1 to 1000"));
    }
    String customerType = blank(body.customerType()) ? null : body.customerType().trim();
    if (customerType != null && !CUSTOMER_TYPES.contains(customerType)) {
      problems.add(new ErrorDetail("customerType", "must be a wallet type or ORG"));
    }
    if (blank(body.text()) || body.text().trim().length() > 500) {
      problems.add(new ErrorDetail("text", "must be 1-500 characters"));
    }
    if (blank(body.reason()) || body.reason().trim().length() > 500) {
      problems.add(new ErrorDetail("reason", "must be 1-500 characters"));
    }
    List<String> msisdns = recipients(body, problems);
    if (msisdns.isEmpty() && customerType == null) {
      problems.add(new ErrorDetail("msisdns", "name recipients, a csv, or a customer type"));
    }
    if (!problems.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The campaign is not usable").withStatus(HttpStatus.BAD_REQUEST)
          .withDetails(problems);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("templateCode", template);
    payload.put("locale", locale);
    payload.put("scheduleAt", body.scheduleAt().trim());
    payload.put("throttlePerSecond", body.throttlePerSecond());
    if (customerType != null) {
      payload.put("customerType", customerType);
    }
    payload.put("msisdns", msisdns);
    payload.put("text", body.text().trim());
    return payload;
  }

  private static List<String> recipients(NotifyCampaignBody body, List<ErrorDetail> problems) {
    Set<String> out = new LinkedHashSet<>();
    if (body.msisdns() != null) {
      for (String raw : body.msisdns()) {
        add(out, raw, problems);
      }
    }
    if (!blank(body.csv())) {
      for (String raw : body.csv().split("[\\n,]+")) {
        if (!raw.isBlank()) {
          add(out, raw, problems);
        }
      }
    }
    if (out.size() > MAX_RECIPIENTS) {
      problems.add(new ErrorDetail("msisdns", "at most " + MAX_RECIPIENTS + " recipients"));
    }
    return List.copyOf(out);
  }

  private static void add(Set<String> out, String raw, List<ErrorDetail> problems) {
    String canonical = canonical(raw);
    if (canonical == null) {
      problems.add(new ErrorDetail("msisdns", "not a mobile number"));
    } else {
      out.add(canonical);
    }
  }

  /** Canonical {@code 01XXXXXXXXX}, or null. {@code 880} / {@code +880} prefixes are accepted. */
  static String canonical(String raw) {
    if (raw == null) {
      return null;
    }
    String digits = raw.replaceAll("\\D", "");
    if (digits.startsWith("880") && digits.length() == 13) {
      digits = "0" + digits.substring(3);
    }
    return MSISDN.matcher(digits).matches() ? digits : null;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
