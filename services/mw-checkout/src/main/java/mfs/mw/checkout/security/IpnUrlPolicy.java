package mfs.mw.checkout.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where a merchant's IPN may be posted (ticket 32; the adapters' rule of ticket 50 plus SSRF
 * guards). The URL comes from the merchant, so it must not reach inside the platform: https only,
 * no user info or fragment, and the host must not be (or resolve to) a loopback, private,
 * link-local, carrier-grade NAT, unspecified or multicast address. Hosts in {@code devHosts}
 * (static config {@code mw.checkout.ipn.dev-hosts}, empty by default; the dev Compose stack and
 * tests name the simulator) may use plain http and a private address.
 *
 * <p>{@link #refusal} is the check when a session is created (no DNS lookup); {@link
 * #refusalAfterLookup} runs before every delivery attempt and also checks every address the name
 * resolves to, so a name pointed at an internal address later is refused then.
 */
public class IpnUrlPolicy {

  /** Name resolution, replaceable in tests. */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private static final Pattern IPV4 = Pattern.compile("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$");

  private final List<String> devHosts;
  private final Resolver resolver;

  public IpnUrlPolicy(List<String> devHosts, Resolver resolver) {
    this.devHosts = devHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
    this.resolver = resolver;
  }

  /** Why {@code url} may not receive IPNs (without a DNS lookup); empty when it may. */
  public Optional<String> refusal(String url) {
    return check(url, false);
  }

  /** As {@link #refusal}, plus every address the host resolves to now. */
  public Optional<String> refusalAfterLookup(String url) {
    return check(url, true);
  }

  private Optional<String> check(String url, boolean lookup) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException | NullPointerException e) {
      return Optional.of("not a URL");
    }
    if (!uri.isAbsolute() || uri.getHost() == null || uri.getScheme() == null) {
      return Optional.of("not an absolute URL with a host");
    }
    if (uri.getRawUserInfo() != null) {
      return Optional.of("must not carry user info");
    }
    if (uri.getRawFragment() != null) {
      return Optional.of("must not carry a fragment");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    boolean devHost = devHosts.contains(host);
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !("http".equals(scheme) && devHost)) {
      return Optional.of("must use https");
    }
    if (devHost) {
      return Optional.empty();
    }
    InetAddress literal = literal(host);
    if (literal != null) {
      return internal(literal) ? Optional.of("must not target an internal address") : Optional.empty();
    }
    if (!lookup) {
      return Optional.empty();
    }
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      return Optional.of("host does not resolve");
    }
    if (addresses == null || addresses.length == 0) {
      return Optional.of("host does not resolve");
    }
    for (InetAddress a : addresses) {
      if (internal(a)) {
        return Optional.of("host resolves to an internal address");
      }
    }
    return Optional.empty();
  }

  /** The address of an IP literal host ({@code 10.0.0.1}, {@code [::1]}), else null. */
  private static InetAddress literal(String host) {
    String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    if (!IPV4.matcher(h).matches() && !h.contains(":")) {
      return null;
    }
    try {
      return InetAddress.ofLiteral(h);
    } catch (IllegalArgumentException e) {
      return InetAddress.getLoopbackAddress(); // unparsable literal: treat as internal
    }
  }

  static boolean internal(InetAddress a) {
    if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
        || a.isMulticastAddress()) {
      return true;
    }
    byte[] b = a.getAddress();
    if (a instanceof Inet6Address) {
      if ((b[0] & 0xfe) == 0xfc) {
        return true; // unique local fc00::/7
      }
      boolean mapped = true;
      for (int i = 0; i < 10; i++) {
        mapped &= b[i] == 0;
      }
      if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
        try {
          return internal(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
        } catch (UnknownHostException e) {
          return true;
        }
      }
      return false;
    }
    return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // carrier-grade NAT 100.64.0.0/10
  }
}
