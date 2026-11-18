package mfs.mw.adapter.billpay.partner.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static partner config from application config ({@code mw.partners}): seeds in force until a
 * {@code mw.config.changed.v1} record replaces them (seeds have version 0, so any event wins), the
 * partner aliases, and the security settings, which only static config (application.yml,
 * per-environment overrides, OpenBao) can set: a config-changed record can never widen them.
 *
 * @param partnerAliases partnerId a channel sends -> the partner serving it (ADR D26: replaces the
 *     silent {@code default-partner}); a partnerId that is neither a partner nor an alias is
 *     refused {@code MW-PTR-UNKNOWN-PARTNER}
 * @param instances partner id to record
 * @param security where partner routes may point
 */
@ConfigurationProperties("mw.partners")
public record PartnerProperties(
    Map<String, String> partnerAliases, Map<String, PartnerConfig> instances, Security security) {

  public PartnerProperties {
    partnerAliases = partnerAliases == null ? Map.of() : Map.copyOf(partnerAliases);
    instances = instances == null ? Map.of() : Map.copyOf(instances);
    security = security == null ? new Security(null, null) : security;
  }

  /**
   * @param allowedHosts optional floor per partner id: when a partner has an entry, its record's
   *     {@code allowedHosts} must lie inside it (exact, case-insensitive); without one, the record's
   *     approved hosts alone apply
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
