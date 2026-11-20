package mfs.mw.admin.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.BillerChangeBody;
import mfs.mw.admin.api.dto.BillerList;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.BillerRepository;
import mfs.mw.admin.api.util.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The biller catalogue (ticket 113, FRS FL-04, FL-05, ADM-05): reads, the maker side of its change
 * requests and the dev seed. Approval is {@link BillerChangeHandler}.
 */
@Service
public class BillerCatalogService {

  public static final String RESOURCE_TYPE = "biller";
  static final String TOPIC = "mw.config.changed.v1";

  private static final Logger log = LoggerFactory.getLogger(BillerCatalogService.class);

  private final ChangeRequestService changes;
  private final BillerRepository billers;
  private final OutboxWriter outbox;
  private final Clock clock;

  public BillerCatalogService(
      ChangeRequestService changes, BillerRepository billers, OutboxWriter outbox, Clock clock) {
    this.changes = changes;
    this.billers = billers;
    this.outbox = outbox;
    this.clock = clock;
  }

  public BillerList list(Operator operator) {
    if (!operator.hasAny(AdminRole.READ_PARTNERS)) {
      throw new ForbiddenException("Reading the biller catalogue needs a partner reading role");
    }
    List<Map<String, Object>> out =
        billers.list().stream()
            .map(
                r -> {
                  Map<String, Object> entry = new LinkedHashMap<>(r.value());
                  entry.put("version", r.version());
                  return entry;
                })
            .toList();
    return new BillerList(out);
  }

  public ChangeRequestView propose(Operator maker, String code, BillerChangeBody body) {
    if (!maker.hasAny(AdminRole.PARTNER_MAKER)) {
      throw new ForbiddenException("Proposing a biller change needs the ops-maker or integration-maker role");
    }
    if (code == null || !BillerRules.CODE.matcher(code).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "A biller code is 2 to 32 capital letters, digits or _");
    }
    String action = body.action() == null ? "UPSERT" : body.action();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", action);
    Map<String, Object> current = billers.find(code).map(BillerRepository.Row::value).orElse(null);
    Map<String, Object> next;
    switch (action) {
      case "UPSERT" -> {
        next = new LinkedHashMap<>(body.value() == null ? Map.of() : body.value());
        next.put("code", code);
        payload.put("value", next);
      }
      case "ENABLE", "DISABLE" -> {
        if (current == null) {
          throw new MwException(ErrorCodes.VAL_NOT_FOUND, "No such biller").withStatus(HttpStatus.NOT_FOUND);
        }
        next = toggled(current, "ENABLE".equals(action), body.maintenanceEn(), body.maintenanceBn());
        if (body.maintenanceEn() != null) {
          payload.put("maintenanceEn", body.maintenanceEn());
        }
        if (body.maintenanceBn() != null) {
          payload.put("maintenanceBn", body.maintenanceBn());
        }
      }
      default -> throw new MwException(ErrorCodes.VAL_INVALID, "action is UPSERT, ENABLE or DISABLE");
    }
    check(next);
    if (next.equals(current)) {
      throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The change changes nothing");
    }
    Map<String, Object> diff = new LinkedHashMap<>();
    diff.put("before", current == null ? Map.of() : current);
    diff.put("after", next);
    return changes.open(maker, RESOURCE_TYPE, code, action, payload, diff, body.reason());
  }

  /** The entry with {@code enabled} set; the maintenance text given replaces the entry's. */
  static Map<String, Object> toggled(Map<String, Object> current, boolean enabled, String en, String bn) {
    Map<String, Object> next = new LinkedHashMap<>(current);
    next.put("enabled", enabled);
    if (en != null) {
      next.put("maintenanceEn", en);
    }
    if (bn != null) {
      next.put("maintenanceBn", bn);
    }
    return next;
  }

  static void check(Map<String, Object> value) {
    List<String> problems = BillerRules.problems(value);
    if (!problems.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The biller is invalid")
          .withDetails(problems.stream().map(p -> new ErrorDetail("value", p)).toList());
    }
  }

  /**
   * Stores a seed entry at version 1 and publishes it, in one transaction, unless the code exists
   * (a seed never overwrites an approved change); true when stored.
   */
  @Transactional
  public boolean seed(Map<String, Object> value) {
    String code = String.valueOf(value.get("code"));
    List<String> problems = BillerRules.problems(value);
    if (!BillerRules.CODE.matcher(code).matches() || !problems.isEmpty()) {
      throw new IllegalStateException("biller seed " + code + " is invalid: " + problems);
    }
    Instant now = clock.instant();
    if (!billers.insertIfAbsent(code, value, now)) {
      return false;
    }
    publish(outbox, code, 1, value, now, "seed", null, CorrelationIds.current(), UUID.randomUUID().toString());
    log.atInfo().addKeyValue("event", "biller.seeded").addKeyValue("billerCode", code).log("biller seed stored");
    return true;
  }

  /** Appends the BILLER UPSERT of {@code value} at {@code version} to the outbox. */
  static void publish(
      OutboxWriter outbox,
      String code,
      int version,
      Map<String, Object> value,
      Instant now,
      String maker,
      String checker,
      String correlationId,
      String txnId) {
    String key = "biller/" + code;
    ConfigChangedEvent event =
        new ConfigChangedEvent(
            1,
            key,
            ConfigChangedEvent.ConfigType.BILLER,
            ConfigChangedEvent.Change.UPSERT,
            version,
            value,
            now,
            maker,
            checker,
            correlationId);
    outbox.append(TOPIC, key, new EventMetadata(correlationId, txnId, key + "@" + version, 1), event);
  }
}
