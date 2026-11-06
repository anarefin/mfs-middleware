package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.PartnerConfigValue;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.entity.Partner;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.PartnerMapper;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.PartnerRepository;
import mfs.mw.admin.api.dto.AdminRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Approving a partner change request (ticket 38, spec 15.3, 20.4), inside the decision
 * transaction: the partner row is locked and must still be at the version the maker saw ({@code
 * payload.baseVersion}; absent: the partner must still not exist), else 409 MW-VAL-030 and the
 * request stays PENDING. The full config ({@code payload.config}) replaces the partner's rows in
 * schema {@code partner}, the version goes up by one, and the config is written to the outbox as a
 * {@code mw.config.changed.v1} PARTNER UPSERT (key {@code partner/<id>}, value the full config,
 * changedBy the maker, approvedBy the checker), so it is published only if the approval commits.
 * The compacted topic keeps the latest record per partner: an adapter starting later replays it.
 */
@Component
public class PartnerChangeHandler implements ResourceChangeHandler {

  public static final String CONFIG_TOPIC = "mw.config.changed.v1";

  private final PartnerRepository partners;
  private final OutboxWriter outbox;
  private final JsonMapper json;

  public PartnerChangeHandler(PartnerRepository partners, OutboxWriter outbox, JsonMapper json) {
    this.partners = partners;
    this.outbox = outbox;
    this.json = json;
  }

  @Override
  public String resourceType() {
    return Partner.RESOURCE_TYPE;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.PARTNER_CHECKER;
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    String id = cr.resourceId();
    Object base = cr.payload().get("baseVersion");
    Long baseVersion = base instanceof Number n ? n.longValue() : null;
    String family = String.valueOf(cr.payload().get("family"));
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>) cr.payload().get("config");
    PartnerConfigValue config = json.convertValue(value, PartnerConfigValue.class);
    return new Approval(
        cr.payload(),
        CONFIG_TOPIC,
        () -> {
          Optional<Partner> held = partners.findForUpdate(id);
          long version;
          if (baseVersion == null) {
            if (held.isPresent()) {
              throw moved();
            }
            version = 1;
            partners.insert(new Partner(id, family, version, config, now, cr.makerId(), checker.username()));
          } else {
            if (held.isEmpty() || held.get().version() != baseVersion) {
              throw moved();
            }
            version = baseVersion + 1;
            if (!partners.update(new Partner(id, family, version, config, now, cr.makerId(), checker.username()), baseVersion)) {
              throw moved();
            }
          }
          ConfigChangedEvent event =
              PartnerMapper.configChanged(id, version, value, cr.makerId(), checker.username(), correlationId, now);
          outbox.append(
              CONFIG_TOPIC, event.configKey(),
              new EventMetadata(correlationId, cr.id().toString(), event.configKey() + "@" + version, 1), event);
          return Map.of("version", version);
        });
  }

  private static MwException moved() {
    return new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The partner changed after this request was made")
        .withStatus(HttpStatus.CONFLICT);
  }
}
