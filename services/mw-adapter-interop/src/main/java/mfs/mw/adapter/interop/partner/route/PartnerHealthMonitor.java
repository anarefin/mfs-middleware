package mfs.mw.adapter.interop.partner.route;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.adapter.interop.partner.PartnerCall;
import mfs.mw.adapter.interop.partner.PartnerResponse;
import mfs.mw.adapter.interop.partner.config.PartnerConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Active partner health (ticket 97, FRS CON-01 healthCheck, CON-04/05), run by the service's
 * ShedLock-guarded {@code scheduler/PartnerHealthJob}: {@link #run} first probes every live,
 * enabled partner whose record says {@code healthCheck: true} (a {@code HEALTH_CHECK} call through
 * its route, as any call: SUCCESS passes, anything else fails), then publishes every partner whose
 * {@link PartnerRouteHealth.State} changed since the last run on the route status sink ({@code
 * mw.partner.route.status.v1} with {@code health}), once per change. Only the instance that holds the
 * lock probes and publishes; the others' {@code /actuator/health} shows breaker and route state only.
 * Call it from a scheduler thread, never a Kafka consumer thread (a probe waits for the partner).
 */
@Component
public class PartnerHealthMonitor {

  private static final Logger log = LoggerFactory.getLogger(PartnerHealthMonitor.class);

  private final PartnerConfigStore store;
  private final PartnerRouteHealth health;
  private final PartnerGateway gateway;
  private final PartnerRouteStatus.Sink sink;
  /** partnerId -> {@code <version>:<state>} last published. */
  private final Map<String, String> published = new ConcurrentHashMap<>();

  public PartnerHealthMonitor(
      PartnerConfigStore store, PartnerRouteHealth health, PartnerGateway gateway, PartnerRouteStatus.Sink sink) {
    this.store = store;
    this.health = health;
    this.gateway = gateway;
    this.sink = sink;
  }

  /** Probes, then publishes the changes. */
  public void run() {
    probe();
    publishChanges();
  }

  void probe() {
    store.entries().values().forEach(entry -> {
      PartnerRuntime runtime = entry.runtime();
      if (runtime == null || runtime.batch() || !runtime.config().enabled() || runtime.config().maintenance()
          || !runtime.config().healthCheck()) {
        return;
      }
      PartnerResponse response = gateway.call(new PartnerCall(
          runtime.id(), PartnerExchange.HEALTH_CHECK, PartnerExchange.HEALTH_CHECK, null, UUID.randomUUID().toString(),
          null, Map.of()));
      boolean passed = response instanceof PartnerResponse.Success;
      health.probed(runtime, passed);
      if (!passed) {
        log.atWarn()
            .addKeyValue("event", "partner.health.probe_failed")
            .addKeyValue("partnerId", runtime.id())
            .addKeyValue("outcome", response.status())
            .log("partner health probe failed");
      }
    });
  }

  void publishChanges() {
    store.entries().forEach((id, entry) -> {
      PartnerRuntime runtime = entry.runtime();
      if (runtime == null) {
        published.remove(id);
        return;
      }
      PartnerRouteHealth.State state = health.health(entry);
      String seen = runtime.version() + ":" + state;
      if (seen.equals(published.get(id))) {
        return;
      }
      try {
        sink.publish(PartnerRouteStatus.health(runtime, state));
        published.put(id, seen);
        log.atInfo()
            .addKeyValue("event", "partner.health.changed")
            .addKeyValue("partnerId", id)
            .addKeyValue("version", runtime.version())
            .addKeyValue("health", state.name())
            .log("partner health changed");
      } catch (RuntimeException e) {
        // tried again on the next run
        log.atWarn()
            .addKeyValue("event", "partner.health.publish_failed")
            .addKeyValue("partnerId", id)
            .addKeyValue("errorClass", e.getClass().getName())
            .log("partner health change could not be published");
      }
    });
  }
}
