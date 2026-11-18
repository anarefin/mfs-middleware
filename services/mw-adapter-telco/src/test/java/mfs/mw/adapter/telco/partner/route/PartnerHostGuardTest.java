package mfs.mw.adapter.telco.partner.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.telco.partner.PartnerTestSeeds;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;
import mfs.mw.adapter.telco.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.telco.partner.config.PartnerProperties;
import org.junit.jupiter.api.Test;

/** The send-time host check (ADR D26 guardrail 4), on the resolved endpoint and the call's runtime. */
class PartnerHostGuardTest {

  final PartnerHostGuard guard =
      new PartnerHostGuard(new PartnerEndpointPolicy(new PartnerProperties.Security(Map.of(), List.of("mw-simulators"))), PartnerTestSeeds.noTlsContexts());
  static final Map.Entry<String, PartnerConfig> SEED = PartnerTestSeeds.devSeed();

  final PartnerRuntime billagg =
      new PartnerRuntime(SEED.getKey(), 0, SEED.getValue(), "partner-" + SEED.getKey() + "-v0",
          "direct:partner-" + SEED.getKey() + "-v0",
          Map.of(), Map.of(), List.of("mw-simulators", "93.184.215.14", "127.0.0.1"), Set.of());

  static final String OPTIONS =
      "?connectTimeout=3000&followRedirects=false&headerFilterStrategy=%23mwPartnerHeaderFilter"
          + "&responseTimeout=5000&soTimeout=5000&throwExceptionOnFailure=false";

  @Test
  void anApprovedHostPasses() {
    assertThat(guard.problem(billagg, "http://mw-simulators:8080" + OPTIONS, null)).isNull();
    assertThat(guard.problem(billagg, "https://93.184.215.14/api" + OPTIONS, null)).isNull();
  }

  @Test
  void anyOtherHostPlainHttpACamelHttpUriOrACallOutsideAPartnerIsRefused() {
    assertThat(guard.problem(billagg, "https://evil.example/api", null)).contains("allowedHosts");
    assertThat(guard.problem(billagg, "http://93.184.215.14/api", null)).contains("plain http");
    assertThat(guard.problem(billagg, "http://mw-simulators:8080", "http://evil.example")).contains("CamelHttpUri");
    assertThat(guard.problem(null, "http://mw-simulators:8080", null)).contains("outside a partner call");
  }

  /** Security review: checked on the resolved address before any I/O, whatever the record approved. */
  @Test
  void anInternalAddressIsRefusedAtSendTimeUnlessTheHostIsAStaticDevHost() {
    assertThat(guard.problem(billagg, "https://127.0.0.1/api" + OPTIONS, null)).contains("internal address");
  }

  /** Security review: only the options mw-http-call sets may reach the http endpoint. */
  @Test
  void anyEndpointOptionOutsideTheFixedSetIsRefused() {
    assertThat(guard.problem(billagg, "http://mw-simulators:8080" + OPTIONS + "&bridgeEndpoint=true", null))
        .contains("option");
    assertThat(guard.problem(billagg, "http://mw-simulators:8080" + OPTIONS.replace("followRedirects=false", "followRedirects=true"), null))
        .contains("option");
    assertThat(guard.problem(billagg, "http://mw-simulators:8080?httpClientConfigurer=%23evil", null)).contains("option");
  }
}
