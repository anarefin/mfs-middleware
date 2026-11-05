package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * A request to a partner adapter on {@code mw.partner.request.<family>.v1} (contract {@code
 * mw.partner.request.v1}, owned by this service; shared payload). Never carries the PIN.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerRequest(
    int schemaVersion,
    String family,
    String requestId,
    String txnId,
    String correlationId,
    Instant issuedAt,
    Instant deadlineAt,
    Payload payload) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(
      String operation,
      String txnType,
      PartnerRef partner,
      String partnerTxnRef,
      Amount amount,
      Party debitParty,
      Party creditParty,
      String originalPartnerRef,
      Map<String, String> attributes) {}
}
