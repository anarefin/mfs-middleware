package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * A partner-initiated message on {@code mw.partner.inbound.v1} (contract {@code
 * mw.partner.inbound.v1}, owned by this service), published by an adapter after it verified the
 * signature and deduplicated on the partner reference. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerInbound(
    int schemaVersion,
    String family,
    String inboundId,
    String txnId,
    String correlationId,
    String partnerId,
    String partnerRef,
    Instant receivedAt,
    Payload payload) {

  /** {@code kind} CALLBACK (outcome of an earlier request), REMITTANCE or NPSB_CREDIT. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Payload(
      String kind,
      String outcome,
      String partnerCode,
      String errorCode,
      String txnType,
      Map<String, String> attributes) {}
}
