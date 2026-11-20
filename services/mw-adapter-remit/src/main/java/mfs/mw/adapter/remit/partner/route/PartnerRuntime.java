package mfs.mw.adapter.remit.partner.route;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.remit.partner.config.PartnerConfig;
import mfs.mw.adapter.remit.partner.mapping.CompiledMapping;
import mfs.mw.adapter.remit.partner.mapping.CompiledStylesheet;

/**
 * One loaded version of a partner record: what its Camel route and the mw-* Kamelets read at call
 * time. Built by {@link PartnerRouteLoader} and replaced as a whole when a new version loads, so a
 * call sees one version from start to end.
 *
 * @param id partner id
 * @param version record version ({@code 0} for the seed)
 * @param config the record
 * @param routeId the partner route's id, {@code partner-<id>-v<version>}
 * @param endpoint the partner route's endpoint, {@code direct:partner-<id>-v<version>}
 * @param mappings the record's JSONata mappings, compiled once
 * @param secrets secret field -> value, read from Vault when the route loaded (never logged)
 * @param allowedHosts the hosts the route may call (record narrowed by the static floor)
 * @param kamelets the mw-* Kamelets the route uses (Kamelet usage, ticket 83)
 * @param stylesheets the XSLT stylesheets {@code mw-soap-call} names (ticket 85): the record's
 *     {@code *.xsl} mappings and {@code classpath:partner/xslt/} resources, compiled once
 */
public record PartnerRuntime(
    String id,
    long version,
    PartnerConfig config,
    String routeId,
    String endpoint,
    Map<String, CompiledMapping> mappings,
    Map<String, String> secrets,
    List<String> allowedHosts,
    Set<String> kamelets,
    Map<String, CompiledStylesheet> stylesheets) {

  public PartnerRuntime {
    mappings = Map.copyOf(mappings);
    stylesheets = Map.copyOf(stylesheets);
    secrets = Map.copyOf(secrets);
    allowedHosts = List.copyOf(allowedHosts);
    kamelets = Set.copyOf(kamelets);
  }

  /** A version without stylesheets (a REST partner). */
  public PartnerRuntime(
      String id, long version, PartnerConfig config, String routeId, String endpoint,
      Map<String, CompiledMapping> mappings, Map<String, String> secrets, List<String> allowedHosts, Set<String> kamelets) {
    this(id, version, config, routeId, endpoint, mappings, secrets, allowedHosts, kamelets, Map.of());
  }

  /** The stylesheet {@code name}; fails when the version has none of that name. */
  public CompiledStylesheet stylesheet(String name) {
    CompiledStylesheet s = stylesheets.get(name);
    if (s == null) {
      throw new IllegalStateException("partner " + id + " has no stylesheet " + name);
    }
    return s;
  }

  /** Route id of version {@code version} of partner {@code id}. */
  public static String routeId(String id, long version) {
    return "partner-" + id + "-v" + version;
  }

  /**
   * Marker of a dry-run candidate's route, endpoint and circuit breaker (ticket 83): {@code ~} is
   * outside the partner id characters ({@code [a-z0-9-]}), so no partner id, whatever it ends with,
   * can produce another partner's candidate names or a live version's.
   */
  public static final String CANDIDATE = "~candidate";

  /** Route id of the dry-run candidate of version {@code version} of partner {@code id}. */
  public static String candidateRouteId(String id, long version) {
    return "partner-" + id + CANDIDATE + "~v" + version;
  }

  /** Endpoint of partner {@code id}'s dry-run candidate (one at a time). */
  public static String candidateEndpoint(String id) {
    return "direct:partner-" + id + CANDIDATE;
  }

  /** The {@code partnerId} a candidate's Kamelets get: it names its circuit breaker {@code mw-cb-<id>~candidate}. */
  public static String candidatePartnerParam(String id) {
    return id + CANDIDATE;
  }

  /** Endpoint of version {@code version} of partner {@code id}. */
  public static String endpoint(String id, long version) {
    return "direct:" + routeId(id, version);
  }

  /**
   * True for a batch route (ticket 86, {@code from: kamelet:mw-sftp-source}): it polls on its own,
   * takes no calls, and runs only while the record is enabled.
   */
  public boolean batch() {
    return kamelets.contains("mw-sftp-source");
  }

  /** The mapping {@code name}; fails when the record has none of that name. */
  public CompiledMapping mapping(String name) {
    CompiledMapping m = mappings.get(name);
    if (m == null) {
      throw new IllegalStateException("partner " + id + " has no mapping " + name);
    }
    return m;
  }

  /** The secret {@code field} read when the route loaded; fails when there is none. */
  public String secret(String field) {
    String value = secrets.get(field);
    if (value == null) {
      throw new IllegalStateException("partner " + id + " has no secret field " + field);
    }
    return value;
  }

  @Override
  public String toString() {
    return "PartnerRuntime[id=" + id + ", version=" + version + ", routeId=" + routeId + "]";
  }
}
