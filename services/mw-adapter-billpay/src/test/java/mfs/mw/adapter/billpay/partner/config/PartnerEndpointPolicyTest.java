package mfs.mw.adapter.billpay.partner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where an approved record may send requests (ADR D26 guardrail 4; security review of ticket 82):
 * the record's hosts are the allowlist (a new biller needs no restart), but a record can never
 * reach the service's own network: loopback, private, link-local (cloud metadata), unspecified and
 * multicast addresses are refused unless the host is on the static dev list; https only, unless the
 * host is an explicitly allowed plain-http dev host; a static floor can only be narrowed.
 */
class PartnerEndpointPolicyTest {

  final PartnerEndpointPolicy policy =
      new PartnerEndpointPolicy(new PartnerProperties.Security(Map.of("billagg", List.of("mw-simulators", "agg.example")),
          List.of("mw-simulators")));

  @ParameterizedTest
  @ValueSource(strings = {
      "https://127.0.0.1", "https://localhost", "https://10.1.2.3", "https://172.16.0.9", "https://192.168.1.1",
      "https://169.254.169.254", "https://0.0.0.0", "https://[::1]", "https://[fd00::1]", "https://[fe80::1]",
      "https://224.0.0.1", "https://100.64.0.1"})
  void aRecordCannotPointAtTheServicesOwnNetwork(String url) {
    List<String> allowed = List.of(URI.create(url).getHost().replace("[", "").replace("]", ""));

    assertThat(policy.problems("waterco", URI.create(url), allowed))
        .anySatisfy(p -> assertThat(p).contains("internal address"));
  }

  @Test
  void aPublicHttpsHostOfTheRecordIsAllowedWithoutAStaticEntry() {
    assertThat(policy.problems("waterco", URI.create("https://93.184.215.14"), List.of("93.184.215.14"))).isEmpty();
  }

  @Test
  void plainHttpIsOnlyForTheStaticDevHosts() {
    assertThat(policy.problems("waterco", URI.create("http://93.184.215.14"), List.of("93.184.215.14")))
        .anySatisfy(p -> assertThat(p).contains("https"));
  }

  @Test
  void theStaticDevListMayBeInternal() {
    assertThat(policy.internalAddressAllowed("billagg", "mw-simulators")).isTrue();
    assertThat(policy.internalAddressAllowed("waterco", "localhost")).isFalse();
  }

  @Test
  void aStaticFloorCanOnlyBeNarrowed() {
    assertThat(policy.allowedHosts("billagg", List.of("agg.example", "evil.example"))).containsExactly("agg.example");
    assertThat(policy.allowedHosts("waterco", List.of("api.waterco.example"))).containsExactly("api.waterco.example");
  }
}
