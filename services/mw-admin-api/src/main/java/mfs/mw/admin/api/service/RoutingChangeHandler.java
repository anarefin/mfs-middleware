package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.RoutingResourceRepository;
import org.springframework.stereotype.Component;

/**
 * Maker-checker for the four routing resources (ticket 98, FRS ADM-04). Approval writes the
 * projection and {@code mw.config.changed.v1} in the decision transaction. The orchestrator applies
 * the event to the tables it routes from.
 */
public class RoutingChangeHandler implements ResourceChangeHandler {

  static final String TOPIC = "mw.config.changed.v1";

  private final RoutingResourceRepository resources;
  private final OutboxWriter outbox;
  private final String type;
  private final String keyPrefix;
  private final ConfigChangedEvent.ConfigType configType;

  RoutingChangeHandler(
      RoutingResourceRepository resources,
      OutboxWriter outbox,
      String type,
      String keyPrefix,
      ConfigChangedEvent.ConfigType configType) {
    this.resources = resources;
    this.outbox = outbox;
    this.type = type;
    this.keyPrefix = keyPrefix;
    this.configType = configType;
  }

  @Override
  public String resourceType() {
    return type;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.PARTNER_CHECKER;
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    return new Approval(cr.payload(), TOPIC, () -> publish(cr, checker, now, correlationId));
  }

  private Map<String, Object> publish(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    boolean delete = "DELETE".equals(cr.action());
    int version =
        resources.findForUpdate(type, cr.resourceId()).map(r -> r.version() + 1).orElse(1);
    Object value = cr.payload().get("value");
    if (delete) {
      resources.delete(type, cr.resourceId());
    } else {
      resources.save(type, cr.resourceId(), version, value, now);
    }
    String key = keyPrefix + "/" + cr.resourceId();
    ConfigChangedEvent event =
        new ConfigChangedEvent(
            1,
            key,
            configType,
            delete ? ConfigChangedEvent.Change.DELETE : ConfigChangedEvent.Change.UPSERT,
            version,
            delete ? null : map(value),
            now,
            cr.makerId(),
            checker.username(),
            correlationId);
    outbox.append(TOPIC, key, new EventMetadata(correlationId, cr.id().toString(), key + "@" + version, 1), event);
    return Map.of("version", version);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> raw) {
      Map<String, Object> out = new LinkedHashMap<>();
      raw.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    throw new MwException(ErrorCodes.VAL_INVALID, "A routing change needs a value object");
  }

  static void requireMaker(Operator maker) {
    if (!maker.hasAny(AdminRole.PARTNER_MAKER)) {
      throw new ForbiddenException("Proposing a routing change needs the ops-maker or integration-maker role");
    }
  }

  @Component
  static class Rules extends RoutingChangeHandler {
    Rules(RoutingResourceRepository resources, OutboxWriter outbox) {
      super(resources, outbox, "ROUTING_RULE", "routing-rule", ConfigChangedEvent.ConfigType.ROUTING_RULE);
    }
  }

  @Component
  static class Prefixes extends RoutingChangeHandler {
    Prefixes(RoutingResourceRepository resources, OutboxWriter outbox) {
      super(resources, outbox, "MSISDN_PREFIX", "msisdn-prefix", ConfigChangedEvent.ConfigType.MSISDN_PREFIX);
    }
  }

  @Component
  static class Overrides extends RoutingChangeHandler {
    Overrides(RoutingResourceRepository resources, OutboxWriter outbox) {
      super(resources, outbox, "MNP_OVERRIDE", "mnp-override", ConfigChangedEvent.ConfigType.MNP_OVERRIDE);
    }
  }

  @Component
  static class Messages extends RoutingChangeHandler {
    Messages(RoutingResourceRepository resources, OutboxWriter outbox) {
      super(resources, outbox, "MAINTENANCE_MESSAGE", "maintenance-message", ConfigChangedEvent.ConfigType.MAINTENANCE_MESSAGE);
    }
  }
}
