package mfs.mw.adapter.billpay.partner.route;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import mfs.mw.adapter.billpay.partner.config.PartnerEndpointPolicy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * The partner HTTP client's resolver (security review of ticket 82, DNS rebinding): every partner
 * connection goes to an address resolved here once and checked by {@link PartnerEndpointPolicy}
 * (no loopback, private, link-local, metadata, transition-range or multicast address), unless the
 * host is a static dev host. {@link PartnerHostGuard}'s check before the send stays as an early
 * refusal; this one is on the very addresses the client connects to. Wired into the {@code
 * http}/{@code https} components by {@link PartnerHttpComponent}.
 */
public final class PartnerDnsResolver implements DnsResolver {

  private final PartnerEndpointPolicy policy;

  public PartnerDnsResolver(PartnerEndpointPolicy policy) {
    this.policy = policy;
  }

  /** True when {@code host} is a static dev host and may resolve to an internal address. */
  public boolean mayBeInternal(String host) {
    return policy.staticHost(host);
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
    if (mayBeInternal(host)) {
      return addresses;
    }
    if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(PartnerEndpointPolicy::internal)) {
      throw new AddressRefusedException(host);
    }
    return addresses;
  }

  @Override
  public String resolveCanonicalHostname(String host) {
    return host;
  }

  /** The host resolved to an internal address: nothing is connected. */
  public static final class AddressRefusedException extends UnknownHostException {
    public AddressRefusedException(String host) {
      super("partner host resolves to an internal address");
    }
  }
}
