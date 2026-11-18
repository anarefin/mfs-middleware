package mfs.mw.adapter.billpay.partner.route;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.config.PartnerProperties;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.apache.camel.model.ModelCamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The partner routes in the running Camel context (ADR D26 lifecycle). {@link #load} checks a
 * record ({@link PartnerRouteLoader}), binds the partner's circuit breaker and adds and starts its
 * route as {@code partner-<id>-v<version>} beside the version in use; nothing changes for calls
 * until the store flips its snapshot. {@link #retire} then stops the old version in the background,
 * waiting up to the record's time limit + 5 s so calls in flight finish on it, and removes it with
 * every route its Kamelets created (routes whose {@code parent} is it, and theirs). The circuit
 * breaker belongs to the partner, not to a version: {@code mw-cb-<id>} in Camel's registry, the
 * Resilience4j instance named by the partner id (its config, or the record's {@code resilience}),
 * so its state survives a swap.
 */
@Component
public class PartnerRoutes implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(PartnerRoutes.class);

  private final CamelContext camel;
  private final PartnerRouteLoader loader;
  private final CircuitBreakerRegistry breakers;
  private final PartnerProperties properties;
  private final ExecutorService retirements = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, String> started = new ConcurrentHashMap<>();

  public PartnerRoutes(
      CamelContext camel, PartnerRouteLoader loader, CircuitBreakerRegistry breakers, PartnerProperties properties) {
    this.camel = camel;
    this.loader = loader;
    this.breakers = breakers;
    this.properties = properties;
  }

  /**
   * Checks and starts version {@code version} of partner {@code id}.
   *
   * @throws mfs.mw.adapter.billpay.partner.config.PartnerConfigRefusedException when the record is
   *     refused (nothing was added)
   */
  public PartnerRuntime load(String id, long version, PartnerConfig config) {
    PartnerRouteLoader.Loaded loaded = loader.load(id, version, config, properties.security());
    bindBreaker(id, loaded.runtime().config());
    return start(id, version, loaded);
  }

  /**
   * Checks and starts the dry-run candidate of version {@code version} of partner {@code id}
   * (ticket 83): its calls go to {@code target}, on a circuit breaker of its own ({@code
   * mw-cb-<id>-candidate}, the default config), so a failing dry-run never opens the live one.
   *
   * @throws mfs.mw.adapter.billpay.partner.config.PartnerConfigRefusedException when refused
   */
  public PartnerRuntime loadCandidate(String id, long version, PartnerConfig config, PartnerRouteLoader.DryRunTarget target) {
    PartnerRouteLoader.Loaded loaded = loader.load(id, version, config, properties.security(), target);
    camel.getRegistry().bind(
        "mw-cb-" + id + PartnerRuntime.CANDIDATE, CircuitBreaker.class,
        CircuitBreaker.of(id + PartnerRuntime.CANDIDATE, breakers.getDefaultConfig()));
    return start(id, version, loaded);
  }

  /** Stops and removes {@code runtime} now (a candidate, or a version that never went live). */
  public void discard(PartnerRuntime runtime) {
    if (runtime != null) {
      remove(runtime.routeId());
    }
  }

  private PartnerRuntime start(String id, long version, PartnerRouteLoader.Loaded loaded) {
    PartnerRuntime runtime = loaded.runtime();
    try {
      ((ModelCamelContext) camel).addRouteDefinition(loaded.route());
      if (camel.getRouteController().getRouteStatus(runtime.routeId()) != ServiceStatus.Started) {
        camel.getRouteController().startRoute(runtime.routeId());
      }
    } catch (Exception e) {
      remove(runtime.routeId());
      throw new IllegalStateException("partner route " + runtime.routeId() + " did not start: " + e.getClass().getSimpleName(), e);
    }
    started.put(runtime.routeId(), id);
    log.atInfo()
        .addKeyValue("event", "partner.route.loaded")
        .addKeyValue("partnerId", id)
        .addKeyValue("version", version)
        .addKeyValue("kamelets", String.join(",", runtime.kamelets()))
        .log("partner route loaded");
    return runtime;
  }

  /** True when the route of {@code runtime} is running. */
  public boolean isStarted(PartnerRuntime runtime) {
    return camel.getRouteController().getRouteStatus(runtime.routeId()) == ServiceStatus.Started;
  }

  /** Stops and removes {@code old} in the background once calls in flight on it have finished. */
  public void retire(PartnerRuntime old) {
    if (old == null) {
      return;
    }
    long timeoutMs = old.config().timeoutMs() + 5000;
    retirements.execute(() -> {
      try {
        camel.getRouteController().stopRoute(old.routeId(), timeoutMs, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        log.atWarn()
            .addKeyValue("event", "partner.route.stop_failed")
            .addKeyValue("partnerId", old.id())
            .addKeyValue("errorClass", e.getClass().getName())
            .log("old partner route did not stop in time");
      }
      remove(old.routeId());
      log.atInfo()
          .addKeyValue("event", "partner.route.retired")
          .addKeyValue("partnerId", old.id())
          .addKeyValue("version", old.version())
          .log("old partner route removed");
    });
  }

  /** Removes route {@code routeId} and every route its Kamelets created. */
  void remove(String routeId) {
    Set<String> ids = new LinkedHashSet<>();
    collect(routeId, ids);
    for (String id : ids) {
      try {
        camel.getRouteController().stopRoute(id, 1, TimeUnit.SECONDS);
        camel.removeRoute(id);
      } catch (Exception e) {
        log.atWarn()
            .addKeyValue("event", "partner.route.remove_failed")
            .addKeyValue("routeId", id)
            .addKeyValue("errorClass", e.getClass().getName())
            .log("partner route could not be removed");
      }
    }
    started.remove(routeId);
  }

  /** {@code routeId} and its Kamelet routes, children first. */
  private void collect(String routeId, Set<String> out) {
    for (Route r : camel.getRoutes()) {
      if (routeId.equals(r.getProperties().get(Route.PARENT_PROPERTY)) && !out.contains(r.getRouteId())) {
        collect(r.getRouteId(), out);
      }
    }
    out.add(routeId);
  }

  /** Every route id that belongs to a partner, with its parent partner route, for tests and health. */
  public Set<String> routeIdsOf(PartnerRuntime runtime) {
    Set<String> ids = new LinkedHashSet<>();
    collect(runtime.routeId(), ids);
    return ids;
  }

  private void bindBreaker(String id, PartnerConfig config) {
    CircuitBreaker breaker;
    PartnerConfig.Resilience r = config.resilience();
    if (r == null) {
      // the instance resilience4j.circuitbreaker.instances.<id> configures, else the default one
      breaker = breakers.circuitBreaker(id);
    } else {
      CircuitBreakerConfig.Builder b = CircuitBreakerConfig.from(breakers.circuitBreaker(id).getCircuitBreakerConfig());
      if (r.failureRateThreshold() != null) b.failureRateThreshold(r.failureRateThreshold());
      if (r.slidingWindowSize() != null) b.slidingWindowSize(r.slidingWindowSize());
      if (r.minimumNumberOfCalls() != null) b.minimumNumberOfCalls(r.minimumNumberOfCalls());
      if (r.slowCallDurationThresholdMs() != null) b.slowCallDurationThreshold(Duration.ofMillis(r.slowCallDurationThresholdMs()));
      if (r.waitDurationInOpenStateMs() != null) b.waitDurationInOpenState(Duration.ofMillis(r.waitDurationInOpenStateMs()));
      breakers.remove(id);
      breaker = breakers.circuitBreaker(id, b.build());
    }
    camel.getRegistry().bind("mw-cb-" + id, CircuitBreaker.class, breaker);
  }

  @Override
  public void close() {
    retirements.close();
  }
}
