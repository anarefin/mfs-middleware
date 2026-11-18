package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * This service's own type for {@code mw.partner.route.status.v1} (contract owned by
 * mw-adapter-billpay, ticket 83): what an adapter made of one approved partner route version.
 * Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerRouteStatusMessage(
    int schemaVersion,
    String partnerId,
    long version,
    String family,
    String status,
    List<String> problems,
    List<String> kamelets,
    String reportedBy,
    Instant reportedAt) {

  public PartnerRouteStatusMessage {
    problems = problems == null ? List.of() : List.copyOf(problems);
    kamelets = kamelets == null ? List.of() : List.copyOf(kamelets);
  }
}
