package mfs.mw.adapter.telco.partner.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static partner config from application config ({@code mw.partners}): seeds in force until a
 * {@code mw.config.changed.v1} record replaces them (seeds have version 0, so any event wins), and
 * the security allowlists, which only static config (application.yml, per-environment overrides,
 * OpenBao) can set: a config-changed record can never widen them.
 *
 * @param defaultPartner instance that serves a partnerId without config of its own (none: such a
 *     call is refused unsent)
 * @param instances partner instance id to config
 * @param security where partner endpoints may point
 */
@ConfigurationProperties("mw.partners")
public record PartnerProperties(String defaultPartner, Map<String, PartnerConfig> instances, Security security) {

  public PartnerProperties {
    instances = instances == null ? Map.of() : Map.copyOf(instances);
    security = security == null ? new Security(null, null) : security;
  }

  /**
   * @param allowedHosts partner instance id to the hosts its {@code baseUrl} may name (exact,
   *     case-insensitive); a partner without an entry cannot be configured at all
   * @param insecureHttpHosts hosts that may be called over plain http (dev/test simulators only;
   *     empty in production); every other host needs https
   */
  public record Security(Map<String, List<String>> allowedHosts, List<String> insecureHttpHosts) {
    public Security {
      allowedHosts = allowedHosts == null ? Map.of() : Map.copyOf(allowedHosts);
      insecureHttpHosts = insecureHttpHosts == null ? List.of() : List.copyOf(insecureHttpHosts);
    }
  }
}
