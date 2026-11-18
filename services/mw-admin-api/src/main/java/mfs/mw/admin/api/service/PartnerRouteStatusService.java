package mfs.mw.admin.api.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.PartnerConfigValue;
import mfs.mw.admin.api.dto.PartnerRouteStatusMessage;
import mfs.mw.admin.api.entity.Partner;
import mfs.mw.admin.api.mapper.PartnerMapper;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.PartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the adapters' route status reports (ticket 83, {@code mw.partner.route.status.v1}) to
 * {@code partner.partner_route}: the version's {@code route_status} (LIVE / REJECTED), its problems
 * and Kamelets. The latest report wins (an adapter that replays the config topic reports again);
 * applying is an update by key, so a redelivered record changes nothing and needs no inbox row. A
 * report for a version not approved here (a seed of the adapter's own config) is logged and skipped.
 *
 * <p>Rollback (ticket 84, ADR D26 amendment): an adapter that restarts replays only the latest
 * record per partner from the compacted {@code mw.config.changed.v1}, so a REJECTED latest version
 * would leave the partner on its seed (or without a route) although an older version ran. A
 * REJECTED report for the partner's <em>current</em> version therefore republishes the route (route
 * and mappings) of the newest version reported LIVE before it, without {@code dryRun} (proven, so
 * the adapter loads it at once), as the next version, in the same transaction as the status update.
 * Only the route comes back: the current enabled flag, timeouts, allowedHosts, auth, headers and
 * error map stay, so a rollback never re-enables a disabled partner or restores old hosts or
 * credentials (review of ticket 84). A rejected version that kept the live route and mappings is
 * not rolled back (the same route would be rejected again, and republishing it could loop):
 * partner rows restored, route row PENDING_DRY_RUN, outbox record changedBy / approvedBy {@value
 * #ROLLBACK_ACTOR}, event {@code partner.route.rolled_back}. A repeated or stale REJECTED (another
 * adapter instance, a replay) finds the version no longer current and does nothing; without a LIVE
 * predecessor nothing is published (the adapter keeps its seed).
 */
@Service
public class PartnerRouteStatusService {

  /** changedBy / approvedBy of a rollback record and the partner rows it restores. */
  public static final String ROLLBACK_ACTOR = "system/rollback";

  private static final Logger log = LoggerFactory.getLogger(PartnerRouteStatusService.class);
  private static final Set<String> STATUSES = Set.of("LIVE", "REJECTED");
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final PartnerRepository partners;
  private final OutboxWriter outbox;
  private final JsonMapper json;
  private final Clock clock;

  public PartnerRouteStatusService(PartnerRepository partners, OutboxWriter outbox, JsonMapper json, Clock clock) {
    this.partners = partners;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
  }

  /** @throws IllegalArgumentException for a report that does not fit the contract (to the DLQ) */
  @Transactional
  public void apply(PartnerRouteStatusMessage m) {
    if (m.partnerId() == null || m.version() < 1 || !STATUSES.contains(m.status())) {
      throw new IllegalArgumentException("not a partner route status");
    }
    boolean known = partners.updateRouteStatus(
        m.partnerId(), m.version(), m.status(), m.problems(), "LIVE".equals(m.status()) ? m.kamelets() : List.of(),
        m.reportedAt() == null ? clock.instant() : m.reportedAt());
    log.atInfo()
        .addKeyValue("event", known ? "partner.route.status" : "partner.route.status_unknown")
        .addKeyValue("partnerId", m.partnerId())
        .addKeyValue("version", m.version())
        .addKeyValue("routeStatus", m.status())
        .addKeyValue("reportedBy", m.reportedBy())
        .log(known ? "partner route status recorded" : "route status for a version not approved here; skipped");
    if (known && "REJECTED".equals(m.status())) {
      rollBack(m.partnerId(), m.version());
    }
  }

  private void rollBack(String partnerId, long rejected) {
    Optional<Partner> held = partners.findForUpdate(partnerId);
    if (held.isEmpty() || held.get().version() != rejected) {
      return;
    }
    Optional<PartnerRepository.LivePublished> live = partners.lastLiveBefore(partnerId, rejected);
    if (live.isEmpty()) {
      log.atInfo()
          .addKeyValue("event", "partner.route.no_rollback")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("version", rejected)
          .log("rejected version has no live predecessor; nothing republished");
      return;
    }
    Partner current = held.get();
    PartnerConfigValue liveConfig = json.convertValue(live.get().value(), PartnerConfigValue.class);
    if (sameRoute(current.config(), liveConfig)) {
      log.atInfo()
          .addKeyValue("event", "partner.route.no_rollback")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("version", rejected)
          .log("rejected version kept the live route; nothing republished");
      return;
    }
    long version = rejected + 1;
    Instant now = clock.instant();
    // only the route comes back: enabled, timeouts, allowedHosts, auth and the rest stay current
    PartnerConfigValue config = current.config()
        .withRoute(liveConfig.route(), liveConfig.mappings(), current.config().allowedHosts(), null);
    Map<String, Object> value = new LinkedHashMap<>(json.convertValue(config, MAP));
    value.put("family", current.family());
    Partner restored = new Partner(partnerId, current.family(), version, config, now, ROLLBACK_ACTOR, ROLLBACK_ACTOR);
    if (!partners.update(restored, rejected)) {
      return;
    }
    partners.insertRoute(restored, value, now);
    String correlationId = Optional.ofNullable(MDC.get("correlationId")).orElse(UUID.randomUUID().toString());
    ConfigChangedEvent event =
        PartnerMapper.configChanged(partnerId, version, value, ROLLBACK_ACTOR, ROLLBACK_ACTOR, correlationId, now);
    outbox.append(
        PartnerChangeHandler.CONFIG_TOPIC, event.configKey(),
        new EventMetadata(
            correlationId,
            UUID.nameUUIDFromBytes(("rollback:" + event.configKey() + "@" + version).getBytes(StandardCharsets.UTF_8)).toString(),
            event.configKey() + "@" + version, 1),
        event);
    log.atWarn()
        .addKeyValue("event", "partner.route.rolled_back")
        .addKeyValue("partnerId", partnerId)
        .addKeyValue("version", version)
        .addKeyValue("rejectedVersion", rejected)
        .addKeyValue("liveVersion", live.get().version())
        .log("rejected partner version rolled back: the last live route is republished");
  }

  /** True when the rejected version left the route and its mappings as the live version had them. */
  private static boolean sameRoute(PartnerConfigValue rejected, PartnerConfigValue live) {
    return Objects.equals(rejected.route(), live.route())
        && Objects.equals(mappings(rejected), mappings(live));
  }

  private static Map<String, String> mappings(PartnerConfigValue c) {
    return c.mappings() == null ? Map.of() : c.mappings();
  }
}
