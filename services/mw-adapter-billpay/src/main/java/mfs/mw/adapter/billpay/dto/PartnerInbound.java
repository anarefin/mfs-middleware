package mfs.mw.adapter.billpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This adapter's own type for {@code mw.partner.inbound.v1} (contract owned by mw-orchestrator):
 * a verified, deduplicated partner callback (family {@code billpay}, kind {@code CALLBACK}).
 * Carries no personal data: references, outcome and codes only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Payload(
      String kind,
      String outcome,
      String partnerCode,
      String errorCode,
      Map<String, String> attributes) {

    public Payload {
      attributes = attributes == null || attributes.isEmpty() ? null : Map.copyOf(attributes);
    }
  }
}
