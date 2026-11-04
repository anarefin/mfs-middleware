package mfs.mw.adapter.telco.partner.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Security rules on where a partner config may send requests, from static config only ({@link
 * PartnerProperties.Security}). Partner config arrives on {@code mw.config.changed.v1}; whoever can
 * publish there must not be able to point a partner (and its credentials) at a host of their
 * choosing. Applied to seeds and to every config-changed record alike: the instance id must be a
 * simple name, the {@code baseUrl} host must be on that instance's allowlist, the scheme must be
 * https (http only for the explicitly allowed dev hosts), and no user info in the URL.
 */
@Component
public class PartnerEndpointPolicy {

  /** Partner instance ids: also the last segment of the secret prefix, so no dots. */
  public static final Pattern INSTANCE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  private final PartnerProperties.Security security;

  @Autowired
  public PartnerEndpointPolicy(PartnerProperties props) {
    this(props.security());
  }

  public PartnerEndpointPolicy(PartnerProperties.Security security) {
    this.security = security;
  }

  /** Every rule {@code baseUrl} of instance {@code id} breaks; empty when allowed. */
  public List<String> problems(String id, URI baseUrl) {
    List<String> problems = new ArrayList<>();
    if (id == null || !INSTANCE_ID.matcher(id).matches()) {
      problems.add("partner id must be lower-case letters, digits, hyphen");
      return problems;
    }
    if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getScheme() == null) {
      return problems; // reported by PartnerConfig.problems()
    }
    String host = baseUrl.getHost().toLowerCase(Locale.ROOT);
    List<String> allowed = security.allowedHosts().getOrDefault(id, List.of());
    if (allowed.stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
      problems.add("baseUrl host is not allowed for partner " + id);
    }
    String scheme = baseUrl.getScheme().toLowerCase(Locale.ROOT);
    boolean insecureAllowed = security.insecureHttpHosts().stream().anyMatch(h -> h.equalsIgnoreCase(host));
    if (!"https".equals(scheme) && !("http".equals(scheme) && insecureAllowed)) {
      problems.add("baseUrl must use https (plain http is not allowed for this host)");
    }
    if (baseUrl.getRawUserInfo() != null) {
      problems.add("baseUrl must not carry user info");
    }
    return problems;
  }
}
