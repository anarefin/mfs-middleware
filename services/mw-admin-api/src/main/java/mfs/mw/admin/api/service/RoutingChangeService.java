package mfs.mw.admin.api.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.RoutingChangeBody;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import org.springframework.stereotype.Service;

/** Opens routing change requests (ticket 98). Approval is {@link RoutingChangeHandler}. */
@Service
public class RoutingChangeService {

  private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  private static final Pattern PREFIX = Pattern.compile("^0[0-9]{2,5}$");
  private static final Pattern MSISDN = Pattern.compile("^01[0-9]{9}$");

  private final ChangeRequestService changes;

  public RoutingChangeService(ChangeRequestService changes) {
    this.changes = changes;
  }

  public ChangeRequestView proposeRule(Operator maker, String id, RoutingChangeBody body) {
    requireId(id);
    if (!"DELETE".equals(body.action())) {
      requireText(body.value(), "txnType");
      requireText(body.value(), "primaryPartnerId");
    }
    return open(maker, "ROUTING_RULE", id, body);
  }

  public ChangeRequestView proposePrefix(Operator maker, String prefix, RoutingChangeBody body) {
    if (!PREFIX.matcher(prefix).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "An MSISDN prefix is 3 to 6 digits starting with 0");
    }
    if (!"DELETE".equals(body.action())) {
      requireText(body.value(), "mno");
    }
    return open(maker, "MSISDN_PREFIX", prefix, body);
  }

  public ChangeRequestView proposeOverride(Operator maker, String msisdn, RoutingChangeBody body) {
    if (!MSISDN.matcher(msisdn).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "An MNP override is an 11-digit mobile number");
    }
    if (!"DELETE".equals(body.action())) {
      requireText(body.value(), "mno");
    }
    return open(maker, "MNP_OVERRIDE", msisdn, body);
  }

  public ChangeRequestView proposeMessage(Operator maker, String id, RoutingChangeBody body) {
    requireId(id);
    if (!"DELETE".equals(body.action())) {
      requireText(body.value(), "messageEn");
      requireText(body.value(), "messageBn");
      Object partner = body.value() == null ? null : body.value().get("partnerId");
      Object biller = body.value() == null ? null : body.value().get("billerCode");
      if ((partner == null || String.valueOf(partner).isBlank()) && (biller == null || String.valueOf(biller).isBlank())) {
        throw new MwException(ErrorCodes.VAL_INVALID, "A maintenance message names a partner or a biller");
      }
    }
    return open(maker, "MAINTENANCE_MESSAGE", id, body);
  }

  private ChangeRequestView open(Operator maker, String type, String id, RoutingChangeBody body) {
    RoutingChangeHandler.requireMaker(maker);
    String action = body.action() == null ? "UPSERT" : body.action();
    if (!"UPSERT".equals(action) && !"DELETE".equals(action)) {
      throw new MwException(ErrorCodes.VAL_INVALID, "action is UPSERT or DELETE");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", action);
    if (body.value() != null) {
      payload.put("value", body.value());
    }
    Map<String, Object> diff = new LinkedHashMap<>();
    diff.put("action", action);
    return changes.open(maker, type, id, action, payload, diff, body.reason());
  }

  private static void requireId(String id) {
    if (id == null || !ID.matcher(id).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The id is lowercase letters, digits and hyphens");
    }
  }

  private static void requireText(Map<String, Object> value, String key) {
    Object raw = value == null ? null : value.get(key);
    if (raw == null || String.valueOf(raw).isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, key + " is required");
    }
  }
}
