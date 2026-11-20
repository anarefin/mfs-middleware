package mfs.mw.adapter.remit.partner.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.adapter.remit.dto.ConfigChangedEvent;
import mfs.mw.adapter.remit.partner.route.PartnerDryRun;
import mfs.mw.adapter.remit.partner.route.PartnerRouteLoader;
import mfs.mw.adapter.remit.partner.route.PartnerRouteStatus;
import mfs.mw.adapter.remit.partner.route.PartnerRoutes;
import mfs.mw.adapter.remit.partner.route.PartnerRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live partner records (spec 20.4, ADR D28): seeded from {@link PartnerProperties} when Camel
 * has started ({@link #start()}), then changed only by {@code mw.config.changed.v1} PARTNER records
 * ({@code configKey} {@code partner/<id>}). Each change loads the new version's route beside the old
 * one ({@link PartnerRoutes#load}), swaps the snapshot in one step (a call sees one version, never a
 * mix) and only then retires the old route, so calls in flight finish on it. A record whose
 * version is not newer than the one held is ignored. A record that is refused (incomplete, a
 * mapping that does not compile, a route outside the allowlist, a host not approved, a secret
 * missing) leaves the last good version live and is logged {@code
 * security.partner_route_refused} ({@code securityRule} true when it broke one). The previous record
 * of each partner is kept in its {@link Entry}.
 *
 * <p>Ticket 83: a record of another family ({@code value.family} not this adapter's) is skipped. A
 * record with a {@code dryRun} goes live only once its dry-run passed: off the Kafka thread (one
 * worker, records in arrival order) the version is loaded beside the live one, its candidate
 * ({@code direct:partner-<id>-candidate}, calls sent to the dry-run target) is sent the sample;
 * pass: the candidate is removed and the version swapped in as above; fail: both are removed and
 * the live version stays. Every record of version 1 or more ends in a {@link PartnerRouteStatus}
 * (LIVE or REJECTED with its problems) for the {@link PartnerRouteStatus.Sink}.
 *
 * <p>A partnerId resolves to its own partner, else to a {@code partner-aliases} entry, else to
 * nothing ({@code MW-PTR-UNKNOWN-PARTNER}): there is no silent default partner (D28).
 */
@Component
public class PartnerConfigStore implements AutoCloseable {

  public static final String KEY_PREFIX = "partner/";

  private static final Logger log = LoggerFactory.getLogger(PartnerConfigStore.class);

  /** The live version (null once deleted), its record version and the record before it. */
  public record Entry(long version, PartnerRuntime runtime, PartnerConfig previous) {}

  private final AtomicReference<Map<String, Entry>> snapshot = new AtomicReference<>(Map.of());
  private final PartnerProperties properties;
  private final PartnerRoutes routes;
  private final JsonMapper json;
  private final PartnerDryRun dryRun;
  private final PartnerRouteStatus.Sink status;
  private final Map<String, ConfigChangedEvent> pending = new LinkedHashMap<>();
  private final ExecutorService dryRuns = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("partner-dry-run").factory());
  private boolean started;

  public PartnerConfigStore(
      PartnerProperties properties, PartnerRoutes routes, JsonMapper json, PartnerDryRun dryRun,
      PartnerRouteStatus.Sink status) {
    this.properties = properties;
    this.routes = routes;
    this.json = json;
    this.dryRun = dryRun;
    this.status = status;
  }

  /**
   * Loads the seeds (version 0; a seed that is refused leaves its partner without a route, logged,
   * never the whole service down), then the records that arrived before Camel had started.
   */
  public void start() {
    Map<String, ConfigChangedEvent> early;
    synchronized (this) {
      Map<String, Entry> seeded = new LinkedHashMap<>();
      properties.instances().forEach((id, config) -> {
        try {
          seeded.put(id, new Entry(0, routes.load(id, 0, config), null));
        } catch (RuntimeException e) {
          // the partner stays DOWN (absent), never the whole service
          log.atError()
              .addKeyValue("event", "security.partner_route_refused")
              .addKeyValue("configKey", KEY_PREFIX + id)
              .addKeyValue("version", 0)
              .addKeyValue("detail", e.getMessage())
              .log("partner seed refused; the partner has no route until a good record arrives");
        }
      });
      snapshot.set(Map.copyOf(seeded));
      started = true;
      early = new LinkedHashMap<>(pending);
      pending.clear();
    }
    early.values().forEach(this::apply);
  }

  /** The live version of partner {@code id}. */
  public Optional<PartnerRuntime> instance(String id) {
    Entry e = id == null ? null : snapshot.get().get(id);
    return e == null ? Optional.empty() : Optional.ofNullable(e.runtime());
  }

  /** The partner serving {@code partnerId}: its own, else its alias, else empty. */
  public Optional<PartnerRuntime> resolve(String partnerId) {
    Optional<PartnerRuntime> own = instance(partnerId);
    if (own.isPresent() || partnerId == null) {
      return own;
    }
    String alias = properties.partnerAliases().get(partnerId);
    return alias == null ? Optional.empty() : instance(alias);
  }

  /** Every partner held, live or deleted. */
  public Map<String, Entry> entries() {
    return snapshot.get();
  }

  /**
   * Applies one config-changed record.
   *
   * @return true when the snapshot changed (false too for a record handed to the dry-run worker)
   */
  public boolean apply(ConfigChangedEvent event) {
    if (event.configType() != ConfigChangedEvent.ConfigType.PARTNER
        || event.configKey() == null
        || !event.configKey().startsWith(KEY_PREFIX)) {
      return false;
    }
    if (event.value() != null && event.value().get("family") instanceof String family
        && !family.equals(properties.family())) {
      log.atDebug()
          .addKeyValue("event", "config.other_family")
          .addKeyValue("configKey", event.configKey())
          .addKeyValue("family", family)
          .log("partner record of another family skipped");
      return false;
    }
    String id = event.configKey().substring(KEY_PREFIX.length());
    PartnerRuntime retired;
    synchronized (this) {
      if (!started) {
        ConfigChangedEvent held = pending.get(id);
        if (held == null || held.version() < event.version()) {
          pending.put(id, event);
        }
        return false;
      }
      Map<String, Entry> current = snapshot.get();
      Entry held = current.get(id);
      if (held != null && event.version() <= held.version()) {
        log.atInfo()
            .addKeyValue("event", "config.stale")
            .addKeyValue("configKey", event.configKey())
            .addKeyValue("version", event.version())
            .addKeyValue("heldVersion", held.version())
            .log("older partner config ignored");
        return false;
      }
      PartnerConfig previous = held == null || held.runtime() == null ? null : held.runtime().config();
      Entry next;
      if (event.change() == ConfigChangedEvent.Change.DELETE) {
        next = new Entry(event.version(), null, previous);
      } else {
        try {
          PartnerConfig config = event.value() == null ? null : json.convertValue(event.value(), PartnerConfig.class);
          if (config == null) {
            refused(event, "an UPSERT needs a value", false);
            status(PartnerRouteStatus.rejected(id, event.version(), List.of("an UPSERT needs a value")));
            return false;
          }
          if (config.dryRun() != null && config.problems().isEmpty()) {
            dryRuns.execute(() -> dryRunThenApply(id, event, config));
            log.atInfo()
                .addKeyValue("event", "partner.dry_run.queued")
                .addKeyValue("configKey", event.configKey())
                .addKeyValue("version", event.version())
                .log("partner record waits for its dry-run");
            return false;
          }
          next = new Entry(event.version(), routes.load(id, event.version(), config), previous);
        } catch (PartnerConfigRefusedException e) {
          refused(event, e.getMessage(), e.security());
          status(PartnerRouteStatus.rejected(id, event.version(), e.problems()));
          return false;
        } catch (RuntimeException e) {
          refused(event, e.getMessage(), false);
          status(PartnerRouteStatus.rejected(id, event.version(), List.of("the route did not start")));
          return false;
        }
      }
      Map<String, Entry> updated = new HashMap<>(current);
      updated.put(id, next);
      snapshot.set(Map.copyOf(updated));
      retired = held == null ? null : held.runtime();
    }
    routes.retire(retired);
    applied(event);
    PartnerRuntime now = instance(id).orElse(null);
    if (now != null) {
      status(PartnerRouteStatus.live(now));
    }
    return true;
  }

  /**
   * The dry-run path (ticket 83), on the dry-run worker: version and candidate loaded, the sample
   * sent through the candidate, then swapped in or both removed. The snapshot is untouched until
   * the dry-run passed, so the live version serves every call meanwhile.
   */
  private void dryRunThenApply(String id, ConfigChangedEvent event, PartnerConfig config) {
    if (stale(id, event)) {
      return;
    }
    PartnerRuntime version;
    try {
      version = routes.load(id, event.version(), config);
    } catch (PartnerConfigRefusedException e) {
      rejected(event, id, e.problems(), e.security());
      return;
    } catch (RuntimeException e) {
      rejected(event, id, List.of("the route did not start"), false);
      return;
    }
    PartnerRouteLoader.DryRunTarget target = dryRun.target(id, config);
    if (target == null) {
      routes.discard(version);
      rejected(event, id, List.of("no dry-run target: set dryRun.baseUrl or mw.partners.dry-run.base-url"), false);
      return;
    }
    PartnerRuntime candidate = null;
    List<String> problems;
    boolean security = false;
    try {
      candidate = routes.loadCandidate(id, event.version(), config, target);
      problems = dryRun.run(candidate);
    } catch (PartnerConfigRefusedException e) {
      problems = e.problems();
      security = e.security();
    } catch (RuntimeException e) {
      problems = List.of("the dry-run did not run: " + e.getClass().getSimpleName());
    } finally {
      routes.discard(candidate);
    }
    if (!problems.isEmpty()) {
      routes.discard(version);
      rejected(event, id, problems, security);
      return;
    }
    PartnerRuntime retired;
    synchronized (this) {
      Map<String, Entry> current = snapshot.get();
      Entry held = current.get(id);
      if (held != null && event.version() <= held.version()) {
        // a newer version went live while this one ran its dry-run
        routes.discard(version);
        return;
      }
      PartnerConfig previous = held == null || held.runtime() == null ? null : held.runtime().config();
      Map<String, Entry> updated = new HashMap<>(current);
      updated.put(id, new Entry(event.version(), version, previous));
      snapshot.set(Map.copyOf(updated));
      retired = held == null ? null : held.runtime();
    }
    routes.retire(retired);
    applied(event);
    status(PartnerRouteStatus.live(version));
  }

  private synchronized boolean stale(String id, ConfigChangedEvent event) {
    Entry held = snapshot.get().get(id);
    return held != null && event.version() <= held.version();
  }

  private void rejected(ConfigChangedEvent event, String id, List<String> problems, boolean security) {
    refused(event, String.join("; ", problems), security);
    status(PartnerRouteStatus.rejected(id, event.version(), problems));
  }

  private void status(PartnerRouteStatus s) {
    if (s.version() < 1) {
      return;
    }
    try {
      status.publish(s);
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "partner.route.status_failed")
          .addKeyValue("partnerId", s.partnerId())
          .addKeyValue("version", s.version())
          .addKeyValue("errorClass", e.getClass().getName())
          .log("partner route status not published");
    }
  }

  private static void applied(ConfigChangedEvent event) {
    log.atInfo()
        .addKeyValue("event", "config.applied")
        .addKeyValue("configKey", event.configKey())
        .addKeyValue("change", event.change())
        .addKeyValue("version", event.version())
        .log("partner config applied");
  }

  @Override
  public void close() {
    dryRuns.shutdownNow();
  }

  private static void refused(ConfigChangedEvent event, String detail, boolean security) {
    // no secret values: refusals name fields and rules only
    log.atWarn()
        .addKeyValue("event", "security.partner_route_refused")
        .addKeyValue("securityRule", security)
        .addKeyValue("configKey", event.configKey())
        .addKeyValue("version", event.version())
        .addKeyValue("changedBy", event.changedBy())
        .addKeyValue("approvedBy", event.approvedBy())
        .addKeyValue("correlationId", event.correlationId())
        .addKeyValue("detail", detail)
        .log("partner record refused, last good version kept");
  }
}
