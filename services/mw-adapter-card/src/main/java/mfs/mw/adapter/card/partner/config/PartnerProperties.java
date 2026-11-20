package mfs.mw.adapter.card.partner.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static partner config from application config ({@code mw.partners}): seeds in force until a
 * {@code mw.config.changed.v1} record replaces them (seeds have version 0, so any event wins), the
 * partner aliases, and the security settings, which only static config (application.yml,
 * per-environment overrides, Vault) can set: a config-changed record can never widen them.
 *
 * @param partnerAliases partnerId a channel sends -> the partner serving it (ADR D28: replaces the
 *     silent {@code default-partner}); a partnerId that is neither a partner nor an alias is
 *     refused {@code MW-PTR-UNKNOWN-PARTNER}
 * @param instances partner id to record
 * @param security where partner routes may point
 * @param family this adapter's partner family ({@code telco}, {@code billpay} or {@code bank};
 *     required, {@code mw.partners.family} in application.yml): a record of another family ({@code
 *     value.family}) is not this adapter's and is skipped, unlogged as a refusal (ticket 83)
 * @param dryRun where dry-runs go (ticket 83)
 */
@ConfigurationProperties("mw.partners")
public record PartnerProperties(
    Map<String, String> partnerAliases,
    Map<String, PartnerConfig> instances,
    Security security,
    String family,
    DryRun dryRun) {

  public PartnerProperties {
    partnerAliases = partnerAliases == null ? Map.of() : Map.copyOf(partnerAliases);
    instances = instances == null ? Map.of() : Map.copyOf(instances);
    security = security == null ? new Security(null, null) : security;
    if (family == null || family.isBlank()) {
      throw new IllegalArgumentException("mw.partners.family is required");
    }
    dryRun = dryRun == null ? new DryRun(null) : dryRun;
  }

  /**
   * Where a candidate's dry-run goes when its record names no sandbox (ticket 83).
   *
   * @param baseUrl {@code {partnerId}} is replaced by the partner id; dev and test point at
   *     mw-simulators' stub ({@code http://mw-simulators:8080/sim/partners/{partnerId}}), whose host
   *     is then added to the candidate's allowed hosts; empty in production (the record's {@code
   *     dryRun.baseUrl} is needed)
   */
  public record DryRun(String baseUrl) {}

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
