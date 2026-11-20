package mfs.mw.adapter.remit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import mfs.mw.adapter.remit.util.Sensitive;
import mfs.mw.adapter.remit.util.SensitiveType;

/**
 * This adapter's own type for {@code mw.partner.inbound.v1} (contract owned by mw-orchestrator): a
 * verified, deduplicated inbound remittance (family {@code remit}, kind {@code REMITTANCE}, txnType
 * {@code REMIT_IN}). The sender's name and country travel in {@code attributes} for the AML screen.
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
      String kind, String txnType, Money amount, Party creditParty, Map<String, String> attributes) {

    public Payload {
      attributes = attributes == null || attributes.isEmpty() ? null : Map.copyOf(attributes);
    }
  }

  /** The canonical {@code Party}: here the beneficiary's customer wallet. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Party(String type, @Sensitive(SensitiveType.MSISDN) String id, String walletType) {}
}
