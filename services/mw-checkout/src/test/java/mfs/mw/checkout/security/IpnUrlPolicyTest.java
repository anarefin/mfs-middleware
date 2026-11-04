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
}
