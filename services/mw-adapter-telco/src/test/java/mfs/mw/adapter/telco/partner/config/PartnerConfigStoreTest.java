package mfs.mw.adapter.telco.partner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.telco.contracts.ServiceJson;
import mfs.mw.adapter.telco.dto.ConfigChangedEvent;
import mfs.mw.adapter.telco.partner.route.PartnerDryRun;
import mfs.mw.adapter.telco.partner.route.PartnerRouteStatus;
import mfs.mw.adapter.telco.partner.route.PartnerRoutes;
import mfs.mw.adapter.telco.partner.route.PartnerRuntime;
import org.junit.jupiter.api.Test;

/**
 * The store's family rule and its LIVE / REJECTED reports for records without a dry-run (ticket
 * 83); the dry-run path runs against Camel in {@code PartnerRouteDryRunIT}.
 */
class PartnerConfigStoreTest {

  final PartnerRoutes routes = mock(PartnerRoutes.class);
  final List<PartnerRouteStatus> statuses = new ArrayList<>();
  final PartnerConfigStore store =
      new PartnerConfigStore(
          new PartnerProperties(null, Map.of(), null, "billpay", null), routes, ServiceJson.MAPPER, mock(PartnerDryRun.class),
          statuses::add);

  static ConfigChangedEvent record(String id, long version, Map<String, Object> value) {
    return new ConfigChangedEvent(
        1, "partner/" + id, ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, version, value,
        Instant.now(), "ops.maker1", "ops.checker1", "corr-1");
  }

  @Test
  void aRecordOfAnotherFamilyIsSkippedUnrefusedAndUnreported() {
    store.start();

    assertThat(store.apply(record("ocs", 1, Map.of("family", "telco", "route", "- route: {}")))).isFalse();

    verify(routes, never()).load(anyString(), anyLong(), any());
    assertThat(statuses).isEmpty();
  }

  @Test
  void anAppliedRecordIsReportedLiveWithItsKameletsAndARefusedOneRejectedWithItsProblems() {
    store.start();
    PartnerConfig config = new PartnerConfig(
        true, "- route: {}", null, List.of("localhost"), "billpay", null, null, null, null, null, null, null, null, null, null, null);
    when(routes.load(anyString(), anyLong(), any()))
        .thenReturn(new PartnerRuntime("newco", 1, config, "partner-newco-v1", "direct:partner-newco-v1", Map.of(),
            Map.of(), List.of("localhost"), Set.of("mw-map", "mw-audit")))
        .thenThrow(new PartnerConfigRefusedException("newco", List.of("'bean' is not allowed in a partner route"), true));

    assertThat(store.apply(record("newco", 1, Map.of("family", "billpay", "route", "- route: {}")))).isTrue();
    assertThat(store.apply(record("newco", 2, Map.of("family", "billpay", "route", "- route: {}")))).isFalse();

    assertThat(statuses).containsExactly(
        new PartnerRouteStatus("newco", 1, PartnerRouteStatus.Status.LIVE, List.of(), List.of("mw-audit", "mw-map")),
        PartnerRouteStatus.rejected("newco", 2, List.of("'bean' is not allowed in a partner route")));
    assertThat(store.instance("newco").orElseThrow().version()).isEqualTo(1);
  }
}
