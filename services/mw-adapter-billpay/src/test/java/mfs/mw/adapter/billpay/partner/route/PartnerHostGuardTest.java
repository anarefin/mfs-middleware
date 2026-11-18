package mfs.mw.adapter.billpay.partner.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.billpay.partner.PartnerTestSeeds;
import mfs.mw.adapter.billpay.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.billpay.partner.config.PartnerProperties;
import org.junit.jupiter.api.Test;

/** The send-time host check (ADR D26 guardrail 4), on the resolved endpoint and the call's runtime. */
class PartnerHostGuardTest {

  final PartnerHostGuard guard =
      new PartnerHostGuard(new PartnerEndpointPolicy(new PartnerProperties.Security(Map.of(), List.of("mw-simulators"))));
  final PartnerRuntime billagg =
      new PartnerRuntime("billagg", 0, PartnerTestSeeds.billagg(), "partner-billagg-v0", "direct:partner-billagg-v0",
          Map.of(), Map.of(), List.of("mw-simulators", "agg.example"), Set.of());

  @Test
  void anApprovedHostPasses() {
    assertThat(guard.problem(billagg, "http://mw-simulators:8080?throwExceptionOnFailure=false", null)).isNull();
    assertThat(guard.problem(billagg, "https://agg.example/api", null)).isNull();
  }

  @Test
  void anyOtherHostPlainHttpACamelHttpUriOrACallOutsideAPartnerIsRefused() {
    assertThat(guard.problem(billagg, "https://evil.example/api", null)).contains("allowedHosts");
    assertThat(guard.problem(billagg, "http://agg.example/api", null)).contains("plain http");
    assertThat(guard.problem(billagg, "http://mw-simulators:8080", "http://evil.example")).contains("CamelHttpUri");
    assertThat(guard.problem(null, "http://mw-simulators:8080", null)).contains("outside a partner call");
  }
}
