package mfs.mw.adapter.telco.partner.route;

import java.net.URI;
import java.util.List;
import mfs.mw.adapter.telco.partner.PartnerCall;
import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;
import mfs.mw.adapter.telco.partner.config.PartnerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The dry-run of a new partner record version (ADR D28 guardrail 3, ticket 83): its candidate
 * route ({@code direct:partner-<id>-candidate}) is sent the record's {@code dryRun.operation} with
 * {@code dryRun.sample} as the canonical input, against the dry-run target, and must answer
 * SUCCESS or PENDING: an outcome {@code mw-classify} accepted against the partner result schema
 * and the record's mappings. Anything else (FAILED, UNKNOWN, NOT_SENT, ...) is a problem and the
 * version is REJECTED.
 *
 * <p>The target: the record's own {@code dryRun.baseUrl} (a partner sandbox, production; it must
 * be in the record's allowed hosts), else the environment's {@code mw.partners.dry-run.base-url}
 * ({@code {partnerId}} replaced; dev: mw-simulators' programmable stub
 * {@code /sim/partners/<id>}), whose host is added to the candidate's allowed hosts. Neither: the
 * version cannot be checked and is REJECTED.
 */
@Component
public class PartnerDryRun {

  /** The {@code txnType} of a dry-run call (metrics and audit tell it from real traffic). */
  public static final String TXN_TYPE = "DRY_RUN";

  private final PartnerProperties properties;
  private final ObjectProvider<PartnerGateway> gateway;

  public PartnerDryRun(PartnerProperties properties, ObjectProvider<PartnerGateway> gateway) {
    this.properties = properties;
    this.gateway = gateway;
  }

  /** Where partner {@code id}'s candidate calls go; null when nothing is configured. */
  public PartnerRouteLoader.DryRunTarget target(String id, PartnerConfig config) {
    PartnerConfig.DryRun dryRun = config.dryRun();
    if (dryRun != null && dryRun.baseUrl() != null && !dryRun.baseUrl().isBlank()) {
      return new PartnerRouteLoader.DryRunTarget(dryRun.baseUrl(), null);
    }
    String base = properties.dryRun().baseUrl();
    if (base == null || base.isBlank()) {
      return null;
    }
    String url = base.replace("{partnerId}", id);
    String host = URI.create(url).getHost();
    return new PartnerRouteLoader.DryRunTarget(url, host == null ? null : host.toLowerCase(java.util.Locale.ROOT));
  }

  /** Sends the dry-run call through {@code candidate}; the problems, empty when it passed. */
  public List<String> run(PartnerRuntime candidate) {
    PartnerConfig.DryRun dryRun = candidate.config().dryRun();
    PartnerCall call =
        new PartnerCall(
            candidate.id(), dryRun.operation(), TXN_TYPE, null, "dryrun-" + candidate.id() + "-v" + candidate.version(),
            "DRYRUN-" + candidate.version(), dryRun.sample());
    PartnerResponse response = gateway.getObject().dryRun(candidate, call);
    return switch (response) {
      case PartnerResponse.Success s -> List.of();
      case PartnerResponse.Pending p -> List.of();
      case PartnerResponse.Rejected r -> List.of("dry-run " + dryRun.operation() + " answered FAILED " + r.code());
      case PartnerResponse.NotSent n -> List.of("dry-run " + dryRun.operation() + " was not sent: " + n.reason());
      case PartnerResponse.Unknown u -> List.of("dry-run " + dryRun.operation() + " gave no usable answer: " + u.reason());
      default -> List.of("dry-run " + dryRun.operation() + " answered " + response.status());
    };
  }
}
