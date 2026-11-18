package mfs.mw.admin.api.service;

import java.time.Clock;
import java.util.Set;
import mfs.mw.admin.api.dto.PartnerRouteStatusMessage;
import mfs.mw.admin.api.repository.PartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the adapters' route status reports (ticket 83, {@code mw.partner.route.status.v1}) to
 * {@code partner.partner_route}: the version's {@code route_status} (LIVE / REJECTED), its problems
 * and Kamelets. The latest report wins (an adapter that replays the config topic reports again);
 * applying is an update by key, so a redelivered record changes nothing and needs no inbox row. A
 * report for a version not approved here (a seed of the adapter's own config) is logged and skipped.
 */
@Service
public class PartnerRouteStatusService {

  private static final Logger log = LoggerFactory.getLogger(PartnerRouteStatusService.class);
  private static final Set<String> STATUSES = Set.of("LIVE", "REJECTED");

  private final PartnerRepository partners;
  private final Clock clock;

  public PartnerRouteStatusService(PartnerRepository partners, Clock clock) {
    this.partners = partners;
    this.clock = clock;
  }

  /** @throws IllegalArgumentException for a report that does not fit the contract (to the DLQ) */
  @Transactional
  public void apply(PartnerRouteStatusMessage m) {
    if (m.partnerId() == null || m.version() < 1 || !STATUSES.contains(m.status())) {
      throw new IllegalArgumentException("not a partner route status");
    }
    boolean known = partners.updateRouteStatus(
        m.partnerId(), m.version(), m.status(), m.problems(), "LIVE".equals(m.status()) ? m.kamelets() : java.util.List.of(),
        m.reportedAt() == null ? clock.instant() : m.reportedAt());
    log.atInfo()
        .addKeyValue("event", known ? "partner.route.status" : "partner.route.status_unknown")
        .addKeyValue("partnerId", m.partnerId())
        .addKeyValue("version", m.version())
        .addKeyValue("routeStatus", m.status())
        .addKeyValue("reportedBy", m.reportedBy())
        .log(known ? "partner route status recorded" : "route status for a version not approved here; skipped");
  }
}
