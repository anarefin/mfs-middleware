package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.BillerRepository;
import org.springframework.stereotype.Component;

/**
 * Approval of a biller catalogue change (ticket 113): in the decision transaction the entry is
 * stored as version + 1 and published on {@code mw.config.changed.v1} BILLER (full value). ENABLE
 * and DISABLE apply to the entry as it is at approval time.
 */
@Component
public class BillerChangeHandler implements ResourceChangeHandler {

  private final BillerRepository billers;
  private final OutboxWriter outbox;

  public BillerChangeHandler(BillerRepository billers, OutboxWriter outbox) {
    this.billers = billers;
    this.outbox = outbox;
  }

  @Override
  public String resourceType() {
    return BillerCatalogService.RESOURCE_TYPE;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.PARTNER_CHECKER;
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    return new Approval(cr.payload(), BillerCatalogService.TOPIC, () -> apply(cr, checker, now, correlationId));
  }

  private Map<String, Object> apply(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    String code = cr.resourceId();
    BillerRepository.Row current = billers.findForUpdate(code).orElse(null);
    Map<String, Object> next;
    if ("UPSERT".equals(cr.action())) {
      next = map(cr.payload().get("value"));
    } else {
      if (current == null) {
        throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The biller no longer exists");
      }
      next =
          BillerCatalogService.toggled(
              current.value(),
              "ENABLE".equals(cr.action()),
              (String) cr.payload().get("maintenanceEn"),
              (String) cr.payload().get("maintenanceBn"));
    }
    BillerCatalogService.check(next);
    int version = current == null ? 1 : current.version() + 1;
    billers.save(code, version, next, now, checker.username());
    BillerCatalogService.publish(outbox, code, version, next, now, cr.makerId(), checker.username(), correlationId, cr.id().toString());
    return Map.of("version", version);
  }

  private static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> raw) {
      Map<String, Object> out = new LinkedHashMap<>();
      raw.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    throw new MwException(ErrorCodes.VAL_INVALID, "A biller change needs a value object");
  }
}
