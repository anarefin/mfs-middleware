package mfs.mw.adapter.billpay.partner.config;

import java.net.URI;
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
 * info. Checked when a route loads ({@code PartnerRouteLoader}) and again before every send
 * ({@code PartnerHostGuard}, on the resolved endpoint).
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
    return id == null || !INSTANCE_ID.matcher(id).matches()
        ? List.of("partner id must be lower-case letters, digits, hyphen")
        : List.of();
  }

  /** Every rule {@code url} breaks for a partner allowed {@code allowed}; empty when allowed. */
  public List<String> problems(URI url, List<String> allowed) {
    List<String> problems = new ArrayList<>();
    if (url == null || url.getHost() == null || url.getScheme() == null) {
      problems.add("url must be an absolute http(s) URL");
      return problems;
    }
    String host = url.getHost().toLowerCase(Locale.ROOT);
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
    return problems;
  }

  /** True when {@code host} may be called over plain http (dev and test simulators). */
  public boolean insecureAllowed(String host) {
    return security.insecureHttpHosts().stream().anyMatch(h -> h.equalsIgnoreCase(host));
  }
}
