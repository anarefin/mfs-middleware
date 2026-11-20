package mfs.mw.admin.api.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mfs.mw.admin.api.dto.ConfigChangedEvent;
import mfs.mw.admin.api.dto.ErrorDetail;
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
 * Ticket 97: the adapters also send health reports (LIVE of the live version with a {@code health}
 * field, version 0 for their seed); they are applied as any LIVE report (the seed's is skipped).
 *
 * <p>Rollback (ticket 84, ADR D28 amendment): an adapter that restarts replays only the latest
 * record per partner from the compacted {@code mw.config.changed.v1}, so a REJECTED latest version
 * would leave the partner on its seed (or without a route) although an older version ran. A
 * REJECTED report for the partner's <em>current</em> version therefore republishes the newest version
 * reported LIVE before it, without {@code dryRun} (proven, so the adapter loads it at once), as the
 * next version, in the same transaction as the status update ({@link #rolledBack}, review of ticket
 * 84): the set its dry-run proved together (route, mappings, allowedHosts, auth, sensitiveFields,
 * headers, error map) comes back as it was, never paired with the rejected version's hosts or auth;
 * the partner is enabled only when both versions are (a rollback never re-enables a partner the
 * operator disabled); the timeouts stay the current ones (operator state). The merged value must
 * pass the route rules again ({@code partner.route.rollback_refused} otherwise). A rejected version
 * that kept that proven set is not rolled back (it would be rejected again, and could loop):
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
  private final PartnerConfigService rules;

  public PartnerRouteStatusService(
      PartnerRepository partners, OutboxWriter outbox, JsonMapper json, Clock clock, PartnerConfigService rules) {
    this.partners = partners;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
    this.rules = rules;
  }

  /** @throws IllegalArgumentException for a report that does not fit the contract (to the DLQ) */
  @Transactional
  public void apply(PartnerRouteStatusMessage m) {
    if (m.partnerId() == null || m.version() < 0 || !STATUSES.contains(m.status())) {
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
    if (sameProvenSet(current.config(), liveConfig)) {
      log.atInfo()
          .addKeyValue("event", "partner.route.no_rollback")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("version", rejected)
          .log("rejected version kept the live route, hosts and auth; nothing republished");
      return;
    }
    long version = rejected + 1;
    Instant now = clock.instant();
    PartnerConfigValue config = rolledBack(current.config(), liveConfig);
    List<ErrorDetail> problems = new ArrayList<>();
    List<ErrorDetail> route = new ArrayList<>();
    rules.validateRoute(partnerId, config, false, problems, route);
    if (!problems.isEmpty() || !route.isEmpty()) {
      log.atError()
          .addKeyValue("event", "partner.route.rollback_refused")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("version", rejected)
          .addKeyValue("liveVersion", live.get().version())
          .addKeyValue("problems", problems.size() + route.size())
          .log("the last live version no longer passes the route rules; nothing republished");
      return;
    }
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

  /**
   * The rollback value: the last LIVE version's config, the set its dry-run proved together (route,
   * mappings, allowedHosts, auth, sensitiveFields, headers, error map), without its dry-run; enabled
   * only when both the live and the current version are (a rollback never re-enables a partner the
   * operator disabled); the timeouts of the current version (operator state, not part of the proof).
   */
  static PartnerConfigValue rolledBack(PartnerConfigValue current, PartnerConfigValue live) {
    return new PartnerConfigValue(
            live.isEnabled() && current.isEnabled(), live.connectTimeoutMs(), live.timeoutMs(), live.readTimeoutMs(),
            live.headers(), live.auth(), live.errorMap(), live.ambiguousCodes(), live.sensitiveFields(), live.resilience(),
            live.route(), live.mappings(), live.allowedHosts(), null)
        .withTimeouts(current.connectTimeoutMs(), current.timeoutMs(), current.readTimeoutMs())
        // ticket 131b: maintenance and the health check are operator state (the current version's);
        // the endpoint profile is part of what the live version proved (the live one's)
        .withHealthFields(current)
        .withEndpoint(live.endpoint(), live.endpoints());
  }

  /**
   * True when the rejected version left the proven set (route, mappings, allowedHosts, auth,
   * sensitiveFields, headers) as the live version had it: republishing it would be rejected again.
   */
  private static boolean sameProvenSet(PartnerConfigValue rejected, PartnerConfigValue live) {
    return Objects.equals(rejected.route(), live.route())
        && Objects.equals(orEmpty(rejected.mappings()), orEmpty(live.mappings()))
        && Objects.equals(rejected.allowedHosts(), live.allowedHosts())
        && Objects.equals(rejected.auth(), live.auth())
        && Objects.equals(orEmpty(rejected.sensitiveFields()), orEmpty(live.sensitiveFields()))
        && Objects.equals(orEmpty(rejected.headers()), orEmpty(live.headers()));
  }

  private static Map<String, String> orEmpty(Map<String, String> m) {
    return m == null ? Map.of() : m;
  }
}
