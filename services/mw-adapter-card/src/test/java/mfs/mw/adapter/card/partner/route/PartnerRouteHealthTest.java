package mfs.mw.adapter.card.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.card.contracts.ServiceJson;
import mfs.mw.adapter.card.partner.PartnerCall;
import mfs.mw.adapter.card.partner.PartnerResponse;
import mfs.mw.adapter.card.partner.config.PartnerConfig;
import mfs.mw.adapter.card.partner.config.PartnerConfigStore;
import mfs.mw.adapter.card.partner.route.PartnerRouteHealth.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Partner health (ticket 97, FRS CON-04/05): the breaker of a partner makes it UP, DEGRADED
 * (half-open, or an error rate over the threshold while closed) or DOWN (open, or a failing health
 * probe); the record's maintenance flag makes it MAINTENANCE; DISABLED and DELETED stay; every
 * change is published once on the route status sink. Identical in telco, billpay and bank.
 */
class PartnerRouteHealthTest {

  final PartnerConfigStore store = mock(PartnerConfigStore.class);
  final PartnerRoutes routes = mock(PartnerRoutes.class);
  final PartnerGateway gateway = mock(PartnerGateway.class);
  final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
  final PartnerRouteHealth health = new PartnerRouteHealth(store, routes, breakers, 25f);
  final List<PartnerRouteStatus> published = new ArrayList<>();
  final PartnerHealthMonitor monitor = new PartnerHealthMonitor(store, health, gateway, published::add);
  final Map<String, PartnerConfigStore.Entry> entries = new LinkedHashMap<>();

  CircuitBreaker breaker;

  static PartnerConfig config(Map<String, Object> extra) {
    Map<String, Object> value = new LinkedHashMap<>(Map.of("route", "- route: {}", "family", "billpay"));
    value.putAll(extra);
    return ServiceJson.MAPPER.convertValue(value, PartnerConfig.class);
  }

  static PartnerConfigStore.Entry entry(long version, PartnerConfig config) {
    return new PartnerConfigStore.Entry(version, new PartnerRuntime(
        "healthco", version, config, PartnerRuntime.routeId("healthco", version),
        PartnerRuntime.endpoint("healthco", version), Map.of(), Map.of(), List.of("localhost"), Set.of("mw-http-call")), null);
  }

  void live(PartnerConfig config) {
    entries.put("healthco", entry(3, config));
  }

  @BeforeEach
  void setUp() {
    breaker = breakers.circuitBreaker("healthco", CircuitBreakerConfig.custom()
        .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(90)
        .waitDurationInOpenState(Duration.ofMinutes(1)).build());
    when(store.entries()).thenReturn(entries);
    when(routes.isStarted(any())).thenReturn(true);
    live(config(Map.of()));
  }

  State state() {
    return health.health(entries.get("healthco"));
  }

  @Test
  void breakerTransitionsMakeThePartnerUpDegradedAndDown() {
    assertThat(state()).isEqualTo(State.UP);

    breaker.transitionToOpenState();
    assertThat(state()).isEqualTo(State.DOWN);

    breaker.transitionToHalfOpenState();
    assertThat(state()).isEqualTo(State.DEGRADED);

    breaker.transitionToClosedState();
    assertThat(state()).isEqualTo(State.UP);
  }

  @Test
  void anErrorRateOverTheThresholdWhileClosedIsDegraded() {
    breaker.onSuccess(1, java.util.concurrent.TimeUnit.MILLISECONDS);
    breaker.onSuccess(1, java.util.concurrent.TimeUnit.MILLISECONDS);
    breaker.onSuccess(1, java.util.concurrent.TimeUnit.MILLISECONDS);
    breaker.onError(1, java.util.concurrent.TimeUnit.MILLISECONDS, new RuntimeException("boom"));

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(state()).isEqualTo(State.DEGRADED);
  }

  @Test
  void aStoppedRouteIsDownAndTheMaintenanceFlagIsMaintenanceAndDisabledStays() {
    when(routes.isStarted(any())).thenReturn(false);
    assertThat(state()).isEqualTo(State.DOWN);

    live(config(Map.of("maintenance", true)));
    assertThat(state()).isEqualTo(State.MAINTENANCE);

    live(config(Map.of("enabled", false, "maintenance", true)));
    assertThat(state()).isEqualTo(State.DISABLED);

    entries.put("healthco", new PartnerConfigStore.Entry(4, null, null));
    assertThat(state()).isEqualTo(State.DELETED);
    assertThat(health.state(entries.get("healthco"))).containsEntry("status", "DELETED");
  }

  @Test
  void aFailingHealthProbeIsDownAndAPassingOneUp() {
    live(config(Map.of("healthCheck", true)));
    when(gateway.call(any(PartnerCall.class))).thenReturn(new PartnerResponse.Unknown(PartnerResponse.UnknownReason.TIMEOUT));

    monitor.run();
    assertThat(state()).isEqualTo(State.DOWN);

    when(gateway.call(any(PartnerCall.class))).thenReturn(new PartnerResponse.Success("health", "0000"));
    monitor.run();
    assertThat(state()).isEqualTo(State.UP);
  }

  @Test
  void aPartnerWithoutAHealthCheckIsNeverProbed() {
    monitor.run();

    org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).call(any());
  }

  @Test
  void eachHealthChangeIsPublishedOnce() {
    monitor.run();
    monitor.run();
    breaker.transitionToOpenState();
    monitor.run();
    monitor.run();

    assertThat(published).extracting(PartnerRouteStatus::health).containsExactly("UP", "DOWN");
    assertThat(published).allSatisfy(s -> {
      assertThat(s.partnerId()).isEqualTo("healthco");
      assertThat(s.version()).isEqualTo(3);
      assertThat(s.status()).isEqualTo(PartnerRouteStatus.Status.LIVE);
      assertThat(s.kamelets()).containsExactly("mw-http-call");
    });
    assertThat(health.state(entries.get("healthco"))).containsEntry("status", "DOWN").containsEntry("circuit", "OPEN");
  }
}
