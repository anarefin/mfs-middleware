package mfs.mw.adapter.card.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.card.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.card.partner.config.PartnerProperties;
import org.junit.jupiter.api.Test;

/**
 * Security review round 2 (DNS rebinding): the partner HTTP client connects only to addresses this
 * resolver resolved once and checked, so a host cannot pass the guard with a public address and
 * then connect to an internal one.
 */
class PartnerDnsResolverTest {

  final PartnerDnsResolver resolver =
      new PartnerDnsResolver(new PartnerEndpointPolicy(new PartnerProperties.Security(
          Map.of("billagg", List.of("agg.internal")), List.of("mw-simulators"))));

  @Test
  void aHostResolvingToAnInternalAddressIsNeverConnected() {
    assertThatThrownBy(() -> resolver.resolve("127.0.0.1")).isInstanceOf(PartnerDnsResolver.AddressRefusedException.class);
    assertThatThrownBy(() -> resolver.resolve("localhost")).isInstanceOf(PartnerDnsResolver.AddressRefusedException.class);
    assertThatThrownBy(() -> resolver.resolve("::ffff:10.0.0.1")).isInstanceOf(PartnerDnsResolver.AddressRefusedException.class);
  }

  @Test
  void aPublicAddressIsReturnedAsChecked() throws Exception {
    assertThat(resolver.resolve("93.184.215.14")).containsExactly(InetAddress.getByName("93.184.215.14"));
  }

  @Test
  void theStaticDevHostsMayBeInternal() {
    assertThat(resolver.mayBeInternal("mw-simulators")).isTrue();
    assertThat(resolver.mayBeInternal("agg.internal")).isTrue();
    assertThat(resolver.mayBeInternal("localhost")).isFalse();
  }
}
