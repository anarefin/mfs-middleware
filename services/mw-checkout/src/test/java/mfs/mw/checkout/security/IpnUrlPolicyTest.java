package mfs.mw.checkout.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where an IPN may go (ticket 32, as the adapters' ticket 50 rule): https only, plain http and
 * private, loopback or link-local targets only for the dev/test hosts named in static config.
 */
class IpnUrlPolicyTest {

  /** A DNS stand-in: names to addresses, everything else unknown. */
  static IpnUrlPolicy.Resolver dns(Map<String, String> names) {
    return host -> {
      String ip = names.get(host);
      if (ip == null) {
        throw new UnknownHostException(host);
      }
      return new InetAddress[] {InetAddress.getByName(ip)};
    };
  }

  static final IpnUrlPolicy.Resolver DNS =
      dns(Map.of(
          "shop.example.test", "203.0.113.10",
          "inside.example.test", "10.1.2.3",
          "rebound.example.test", "169.254.169.254",
          "mw-simulators", "172.18.0.5"));

  private final IpnUrlPolicy strict = new IpnUrlPolicy(List.of(), DNS);
  private final IpnUrlPolicy dev = new IpnUrlPolicy(List.of("mw-simulators"), DNS);

  @Test
  void aPublicHttpsUrlIsAllowed() {
    assertThat(strict.refusal("https://shop.example.test/ipn?x=1")).isEmpty();
    assertThat(strict.refusalAfterLookup("https://shop.example.test/ipn")).isEmpty();
  }

  @Test
  void plainHttpIsRefusedUnlessTheHostIsAnAllowedDevHost() {
    assertThat(strict.refusal("http://shop.example.test/ipn")).isPresent();
    assertThat(dev.refusal("http://shop.example.test/ipn")).isPresent();
    assertThat(strict.refusal("http://mw-simulators:8080/merchant/ipn")).isPresent();
    assertThat(dev.refusal("http://mw-simulators:8080/merchant/ipn")).isEmpty();
    assertThat(dev.refusalAfterLookup("http://MW-SIMULATORS:8080/merchant/ipn")).as("private address allowed for it").isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "https://127.0.0.1/ipn", "https://10.0.0.1/ipn", "https://172.16.4.4/ipn", "https://192.168.1.1/ipn",
    "https://169.254.169.254/latest/meta-data", "https://0.0.0.0/ipn", "https://[::1]/ipn",
    "https://[fe80::1]/ipn", "https://[fd00::1]/ipn", "https://[::ffff:127.0.0.1]/ipn", "https://100.64.0.1/ipn",
    "https://224.0.0.1/ipn"
  })
  void literalInternalAddressesAreRefused(String url) {
    assertThat(strict.refusal(url)).isPresent();
  }

  @Test
  void namesThatResolveToInternalAddressesAreRefusedAtDelivery() {
    assertThat(strict.refusal("https://inside.example.test/ipn")).as("no lookup when the session is created").isEmpty();
    assertThat(strict.refusalAfterLookup("https://inside.example.test/ipn")).isPresent();
    assertThat(strict.refusalAfterLookup("https://rebound.example.test/ipn")).isPresent();
    assertThat(strict.refusalAfterLookup("https://mw-simulators/ipn")).isPresent();
    assertThat(strict.refusalAfterLookup("https://nowhere.example.test/ipn")).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "ftp://shop.example.test/ipn", "https://user:pw@shop.example.test/ipn", "https://shop.example.test/ipn#x",
    "/relative/ipn", "not a url", ""
  })
  void malformedUrlsAreRefused(String url) {
    assertThat(dev.refusal(url)).isPresent();
  }

  // --- IPv6 (review A7): embedded IPv4 extracted and checked, else global unicast 2000::/3 only ---

  @ParameterizedTest
  @ValueSource(strings = {
    // NAT64 well-known prefix 64:ff9b::/96 with an internal IPv4 inside
    "https://[64:ff9b::a00:1]/ipn", "https://[64:ff9b::7f00:1]/ipn", "https://[64:ff9b::a9fe:a9fe]/ipn",
    // NAT64 local-use 64:ff9b:1::/48 (RFC 8215): where the IPv4 sits depends on the local prefix
    // length, so no embedding can be trusted; refused whatever it carries (10.0.0.1 as for /48 and
    // /96 prefixes, 8.8.8.8 as for a /48 one)
    "https://[64:ff9b:1:a00:0:100::]/ipn", "https://[64:ff9b:1::a00:1]/ipn", "https://[64:ff9b:1:808:8:800::]/ipn",
    // 6to4 2002::/16 with 10.0.0.1 and 127.0.0.1
    "https://[2002:a00:1::1]/ipn", "https://[2002:7f00:1::]/ipn",
    // IPv4-compatible ::/96 with 10.0.0.1 and 192.168.1.1
    "https://[::a00:1]/ipn", "https://[::c0a8:101]/ipn"
  })
  void anInternalIpv4EmbeddedInAnIpv6AddressIsRefused(String url) {
    assertThat(strict.refusal(url)).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "https://[64:ff9b::808:808]/ipn", "https://[2002:808:808::1]/ipn",
    "https://[::808:808]/ipn"
  })
  void aPublicIpv4EmbeddedInAnIpv6AddressIsAllowed(String url) {
    assertThat(strict.refusal(url)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    // discard-only 100::/64, documentation 2001:db8::/32
    "https://[100::1]/ipn", "https://[2001:db8::1]/ipn", "https://[2001:db8:ffff::5]/ipn",
    // outside global unicast 2000::/3
    "https://[4000::1]/ipn", "https://[e000::1]/ipn",
    // IETF protocol assignments 2001::/23 (Teredo 2001::/32, benchmarking 2001:2::/48, ORCHID)
    "https://[2001::1]/ipn", "https://[2001:2::1]/ipn", "https://[2001:10::1]/ipn"
  })
  void ipv6OutsidePublicGlobalUnicastIsRefused(String url) {
    assertThat(strict.refusal(url)).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://[2606:4700:4700::1111]/ipn", "https://[2a00:1450:4001::200e]/ipn",
    "https://[3fff:ffff::1]/ipn"})
  void publicGlobalUnicastIpv6IsAllowed(String url) {
    assertThat(strict.refusal(url)).isEmpty();
  }

  @Test
  void theLookupResolvesOnceAndHandsBackEveryCheckedAddress() throws Exception {
    java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
    InetAddress a = InetAddress.getByName("203.0.113.10");
    InetAddress b = InetAddress.getByName("2606:4700:4700::1111");
    IpnUrlPolicy policy = new IpnUrlPolicy(List.of(), host -> {
      lookups.incrementAndGet();
      return new InetAddress[] {a, b};
    });

    IpnUrlPolicy.Decision d = policy.lookup("https://shop.example.test:8443/ipn?x=1");

    assertThat(d.refused()).isFalse();
    assertThat(d.uri().getHost()).isEqualTo("shop.example.test");
    assertThat(d.addresses()).containsExactly(a, b);
    assertThat(lookups.get()).isEqualTo(1);
  }

  @Test
  void oneInternalAddressAmongPublicOnesRefusesTheHost() throws Exception {
    InetAddress[] mixed = {InetAddress.getByName("203.0.113.10"), InetAddress.getByName("64:ff9b::a00:1")};
    IpnUrlPolicy policy = new IpnUrlPolicy(List.of(), host -> mixed);
    IpnUrlPolicy.Decision d = policy.lookup("https://shop.example.test/ipn");
    assertThat(d.refused()).isTrue();
    assertThat(d.addresses()).isEmpty();
  }

  @Test
  void aNameResolvingToADocumentationIpv6IsRefusedAtDelivery() throws Exception {
    InetAddress doc = InetAddress.getByName("2001:db8::5");
    IpnUrlPolicy policy = new IpnUrlPolicy(List.of(), host -> new InetAddress[] {doc});
    assertThat(policy.refusalAfterLookup("https://shop.example.test/ipn")).isPresent();
  }

  @Test
  void aDevHostIsResolvedAndPinnedWithoutTheAddressChecks() {
    IpnUrlPolicy.Decision d = dev.lookup("http://mw-simulators:8080/merchant/ipn");
    assertThat(d.refused()).isFalse();
    assertThat(d.addresses()).extracting(InetAddress::getHostAddress).containsExactly("172.18.0.5");
  }

  @Test
  void aLiteralAddressIsItsOwnPinnedAddress() {
    IpnUrlPolicy.Decision d = strict.lookup("https://[2606:4700:4700::1111]/ipn");
    assertThat(d.refused()).isFalse();
    assertThat(d.addresses()).extracting(InetAddress::getHostAddress).containsExactly("2606:4700:4700:0:0:0:0:1111");
  }
}
