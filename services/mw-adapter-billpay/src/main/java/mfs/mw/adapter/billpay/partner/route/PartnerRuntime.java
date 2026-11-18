package mfs.mw.adapter.billpay.partner.route;

import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.mapping.CompiledMapping;

/**
 * One loaded version of a partner record: what its Camel route and the mw-* Kamelets read at call
 * time. Built by {@link PartnerRouteLoader} and replaced as a whole when a new version loads, so a
 * call sees one version from start to end.
 *
 * @param id partner id ({@code billagg})
 * @param version record version ({@code 0} for the seed)
 * @param config the record (for a legacy record, after its translation to a route)
 * @param routeId the partner route's id, {@code partner-<id>-v<version>}
 * @param endpoint the partner route's endpoint, {@code direct:partner-<id>-v<version>}
 * @param mappings the record's JSONata mappings, compiled once
 * @param secrets secret field -> value, read from OpenBao when the route loaded (never logged)
 * @param allowedHosts the hosts the route may call (record narrowed by the static floor)
 * @param kamelets the mw-* Kamelets the route uses (Kamelet usage, ticket 83)
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
    Set<String> kamelets) {

  public PartnerRuntime {
    mappings = Map.copyOf(mappings);
    secrets = Map.copyOf(secrets);
    allowedHosts = List.copyOf(allowedHosts);
    kamelets = Set.copyOf(kamelets);
  }

  /** Route id of version {@code version} of partner {@code id}. */
  public static String routeId(String id, long version) {
    return "partner-" + id + "-v" + version;
  }

  /** Suffix of a dry-run candidate's route, endpoint and circuit breaker (ticket 83). */
  public static final String CANDIDATE = "-candidate";

  /** Route id of the dry-run candidate of version {@code version} of partner {@code id}. */
  public static String candidateRouteId(String id, long version) {
    return "partner-" + id + CANDIDATE + "-v" + version;
  }

  /** Endpoint of partner {@code id}'s dry-run candidate (one at a time). */
  public static String candidateEndpoint(String id) {
    return "direct:partner-" + id + CANDIDATE;
  }

  /** Endpoint of version {@code version} of partner {@code id}. */
  public static String endpoint(String id, long version) {
    return "direct:" + routeId(id, version);
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
