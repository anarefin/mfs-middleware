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
 * no user info or fragment, and the host must not be (or resolve to) an internal address. Hosts in
 * {@code devHosts} (static config {@code mw.checkout.ipn.dev-hosts}, empty by default; the dev
 * Compose stack and tests name the simulator) may use plain http and a private address.
 *
 * <p>Internal: IPv4 loopback, private, link-local, carrier-grade NAT, unspecified and multicast.
 * IPv6 (review A7): an IPv4 embedded by NAT64 {@code 64:ff9b::/96}, 6to4 {@code 2002::/16} or the
 * IPv4-compatible {@code ::/96} is extracted and checked as IPv4; the NAT64 local-use prefix
 * {@code 64:ff9b:1::/48} is refused outright (where its IPv4 sits depends on a local prefix length,
 * RFC 8215, so no extraction can be trusted); otherwise only global unicast {@code 2000::/3} is
 * accepted, minus documentation {@code 2001:db8::/32} and the IETF protocol block {@code 2001::/23}
 * (Teredo, benchmarking, ORCHID); discard-only {@code 100::/64} and every other block are refused.
 *
 * <p>{@link #refusal} is the check when a session is created (no DNS lookup). {@link #lookup} runs
 * before every delivery attempt: it resolves the host once, checks every address, and hands back
 * those addresses, so the client connects to exactly what was checked (no second lookup that DNS
 * rebinding could answer differently).
 */
public class IpnUrlPolicy {

  /** Name resolution, replaceable in tests. */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  /**
   * The outcome of {@link #lookup}: a refusal reason, or the URL and the checked addresses its host
   * resolved to (the only ones the delivery may connect to).
   */
  public record Decision(String refusal, URI uri, List<InetAddress> addresses) {

    public Decision {
      addresses = addresses == null ? List.of() : List.copyOf(addresses);
    }

    static Decision refuse(String reason) {
      return new Decision(reason, null, List.of());
    }

    public boolean refused() {
      return refusal != null;
    }
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
    return Optional.ofNullable(check(url, false).refusal());
  }

  /** As {@link #refusal}, plus every address the host resolves to now. */
  public Optional<String> refusalAfterLookup(String url) {
    return Optional.ofNullable(lookup(url).refusal());
  }

  /** Checks {@code url}, resolves its host once and checks every address; see {@link Decision}. */
  public Decision lookup(String url) {
    return check(url, true);
  }

  private Decision check(String url, boolean lookup) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException | NullPointerException e) {
      return Decision.refuse("not a URL");
    }
    if (!uri.isAbsolute() || uri.getHost() == null || uri.getScheme() == null) {
      return Decision.refuse("not an absolute URL with a host");
    }
    if (uri.getRawUserInfo() != null) {
      return Decision.refuse("must not carry user info");
    }
    if (uri.getRawFragment() != null) {
      return Decision.refuse("must not carry a fragment");
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    boolean devHost = devHosts.contains(host);
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !("http".equals(scheme) && devHost)) {
      return Decision.refuse("must use https");
    }
    InetAddress literal = devHost ? null : literal(host);
    if (literal != null) {
      return internal(literal)
          ? Decision.refuse("must not target an internal address")
          : new Decision(null, uri, List.of(literal));
    }
    if (!lookup) {
      return new Decision(null, uri, List.of());
    }
    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException e) {
      return Decision.refuse("host does not resolve");
    }
    if (addresses == null || addresses.length == 0) {
      return Decision.refuse("host does not resolve");
    }
    if (!devHost) {
      for (InetAddress a : addresses) {
        if (internal(a)) {
          return Decision.refuse("host resolves to an internal address");
        }
      }
    }
    return new Decision(null, uri, List.of(addresses));
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
      return internalIpv6(b);
    }
    return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // carrier-grade NAT 100.64.0.0/10
  }

  private static boolean internalIpv6(byte[] b) {
    // IPv4-mapped ::ffff:0:0/96 (the JDK usually hands these over as IPv4 already)
    if (zero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
      return internalIpv4(b, 12);
    }
    // IPv4-compatible ::/96 (:: and ::1 were caught as unspecified and loopback)
    if (zero(b, 0, 12)) {
      return internalIpv4(b, 12);
    }
    // NAT64 well-known prefix 64:ff9b::/96
    if (prefix(b, 0x00, 0x64, 0xff, 0x9b) && zero(b, 4, 12)) {
      return internalIpv4(b, 12);
    }
    // NAT64 local-use 64:ff9b:1::/48: refused whatever it embeds (see the class comment)
    if (prefix(b, 0x00, 0x64, 0xff, 0x9b, 0x00, 0x01)) {
      return true;
    }
    // 6to4 2002::/16: the IPv4 follows the prefix
    if (prefix(b, 0x20, 0x02)) {
      return internalIpv4(b, 2);
    }
    // only global unicast 2000::/3 is reachable (this also refuses discard-only 100::/64)
    if ((b[0] & 0xe0) != 0x20) {
      return true;
    }
    // documentation 2001:db8::/32
    if (prefix(b, 0x20, 0x01, 0x0d, 0xb8)) {
      return true;
    }
    // IETF protocol assignments 2001::/23 (Teredo 2001::/32, benchmarking, ORCHID)
    return (b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xfe) == 0x00;
  }

  private static boolean internalIpv4(byte[] b, int from) {
    try {
      return internal(InetAddress.getByAddress(new byte[] {b[from], b[from + 1], b[from + 2], b[from + 3]}));
    } catch (UnknownHostException e) {
      return true;
    }
  }

  private static boolean zero(byte[] b, int from, int to) {
    for (int i = from; i < to; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean prefix(byte[] b, int... bytes) {
    for (int i = 0; i < bytes.length; i++) {
      if ((b[i] & 0xff) != bytes[i]) {
        return false;
      }
    }
    return true;
  }
}
