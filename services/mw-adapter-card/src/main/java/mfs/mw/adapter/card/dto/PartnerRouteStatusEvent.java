package mfs.mw.adapter.card.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * {@code mw.partner.route.status.v1} (own contract, ticket 83): what became of one partner record
 * version in this adapter, LIVE or REJECTED with its problems, for mw-admin-api's console; since
 * ticket 97 also a health report (LIVE with {@code health}, a seed's version 0) on every change. Key:
 * partnerId. Carries no secret and no personal data: problems name fields and rules only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerRouteStatusEvent(
    int schemaVersion,
    String partnerId,
    long version,
    String family,
    String status,
    List<String> problems,
    List<String> kamelets,
    String health,
    String reportedBy,
    Instant reportedAt) {

  public PartnerRouteStatusEvent {
    problems = problems == null ? List.of() : List.copyOf(problems);
    kamelets = kamelets == null ? List.of() : List.copyOf(kamelets);
  }
}
