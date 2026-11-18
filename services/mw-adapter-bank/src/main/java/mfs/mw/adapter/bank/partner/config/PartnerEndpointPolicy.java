package mfs.mw.adapter.bank.partner.config;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Where a partner route may send requests (ADR D26, guardrail 4). The hosts come from the approved
 * record ({@code allowedHosts}); the static {@code mw.partners.security.allowed-hosts} of a partner,
 * when set, is a floor the record cannot leave. Every URL a route calls ({@code mw-http-call?url=})
 * must name one of them, over https (http only for the explicitly allowed dev hosts), without user
 * info, and must not resolve to the service's own network (loopback, private, shared, link-local
 * such as the cloud metadata address, unspecified, multicast) unless the host is on the static dev
 * list ({@code insecure-http-hosts} or the partner's floor): an approved record can name a new
 * biller's host without a restart, never an internal one (security review, ticket 82). Checked
 * when a route loads ({@code PartnerRouteLoader}) and again before every send ({@code
 * PartnerHostGuard}, on the addresses the host resolves to then; the JVM's DNS cache hands the
 * HTTP client the same addresses).
 */
@Component
public class PartnerEndpointPolicy {

  /** Partner ids: also a segment of the secret path and of route ids, so no dots or slashes. */
  public static final Pattern INSTANCE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  private final PartnerProperties.Security security;

  @Autowired
  public PartnerEndpointPolicy(PartnerProperties props) {
    this(props.security());
  }

  public PartnerEndpointPolicy(PartnerProperties.Security security) {
    this.security = security;
  }

  /**
   * The hosts partner {@code id} may call: its record's hosts, narrowed to the static floor when
   * the partner has one.
   */
  public List<String> allowedHosts(String id, List<String> recordHosts) {
    List<String> floor = security.allowedHosts().get(id);
    return recordHosts.stream()
        .filter(h -> floor == null || floor.stream().anyMatch(f -> f.equalsIgnoreCase(h)))
        .toList();
  }

  /** Every rule the partner id breaks; empty when it is a simple name. */
  public List<String> idProblems(String id) {
    // a dry-run candidate's names carry "~", outside these characters (ticket 83)
    return id == null || !INSTANCE_ID.matcher(id).matches()
        ? List.of("partner id must be lower-case letters, digits, hyphen")
        : List.of();
  }

  /** Every rule {@code url} breaks for partner {@code id} allowed {@code allowed}; empty when allowed. */
  public List<String> problems(String id, URI url, List<String> allowed) {
    List<String> problems = new ArrayList<>();
    if (url == null || url.getHost() == null || url.getScheme() == null) {
      problems.add("url must be an absolute http(s) URL");
      return problems;
    }
    String host = bare(url.getHost());
    if (allowed.stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
      problems.add("url host is not in the partner's allowedHosts");
    }
    String scheme = url.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !("http".equals(scheme) && insecureAllowed(host))) {
      problems.add("url must use https (plain http is not allowed for this host)");
    }
    if (url.getRawUserInfo() != null) {
      problems.add("url must not carry user info");
    }
    if (url.getRawQuery() != null || url.getRawFragment() != null) {
      problems.add("url must not carry a query or fragment");
    }
    if (!internalAddressAllowed(id, host)) {
      String address = addressProblem(host);
      if (address != null) {
        problems.add(address);
      }
    }
    return problems;
  }

  /** {@code host} lower case, an IPv6 literal without its brackets. */
  public static String bare(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
  }

  /** True when {@code host} is on the static dev list (plain-http hosts, the partner's floor). */
  public boolean internalAddressAllowed(String id, String host) {
    List<String> floor = security.allowedHosts().getOrDefault(id, List.of());
    return insecureAllowed(host) || floor.stream().anyMatch(h -> h.equalsIgnoreCase(host));
  }

  /** Why {@code host} may not be called (it does not resolve, or to an internal address); null when it may. */
  public String addressProblem(String host) {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
    } catch (UnknownHostException e) {
      return "url host does not resolve";
    }
    for (InetAddress a : addresses) {
      if (internal(a)) {
        return "url host resolves to an internal address (loopback, private, link-local, metadata, multicast)";
      }
    }
    return null;
  }

  /** True when {@code host} is on the static dev list of any partner (plain-http hosts, floors). */
  public boolean staticHost(String host) {
    return insecureAllowed(host)
        || security.allowedHosts().values().stream().flatMap(List::stream).anyMatch(h -> h.equalsIgnoreCase(host));
  }

  /**
   * True for an address a partner may never be: IPv4 loopback, private, shared, link-local
   * (metadata), this-network, reserved, multicast; IPv6 anything but global unicast {@code 2000::/3},
   * and in it the transition ranges that embed an IPv4 address (6to4 {@code 2002::/16}, Teredo
   * {@code 2001::/32}). IPv4-mapped addresses come as IPv4 ({@code InetAddress}); IPv4-compatible,
   * NAT64 ({@code 64:ff9b::/96}, {@code 64:ff9b:1::/48}), loopback, unspecified, link-local,
   * site-local, ULA and multicast are all outside {@code 2000::/3}.
   */
  public static boolean internal(InetAddress a) {
    if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
        || a.isMulticastAddress()) {
      return true;
    }
    byte[] b = a.getAddress();
    if (a instanceof Inet6Address) {
      boolean globalUnicast = (b[0] & 0xe0) == 0x20;
      boolean sixToFour = (b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02;
      boolean teredo = (b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0 && b[3] == 0;
      return !globalUnicast || sixToFour || teredo;
    }
    int first = b[0] & 0xff;
    int second = b[1] & 0xff;
    return first == 0 // this network
        || (first == 100 && second >= 64 && second <= 127) // shared address space 100.64.0.0/10
        || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // IETF protocol assignments
        || first >= 240; // reserved, broadcast
  }

  /** True when {@code host} may be called over plain http (dev and test simulators). */
  public boolean insecureAllowed(String host) {
    return security.insecureHttpHosts().stream().anyMatch(h -> h.equalsIgnoreCase(host));
  }
}
