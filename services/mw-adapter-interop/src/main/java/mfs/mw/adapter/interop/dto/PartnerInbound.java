package mfs.mw.adapter.interop.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import mfs.mw.adapter.interop.util.Sensitive;
import mfs.mw.adapter.interop.util.SensitiveType;

/**
 * This adapter's own type for {@code mw.partner.inbound.v1} (contract owned by mw-orchestrator): a
 * verified, deduplicated NPSB credit (ticket 121b: family {@code interop}, kind {@code NPSB_CREDIT}, txnType
 * {@code NPSB_IN}). The ISO ids, the debtor and its participant travel in {@code attributes}.
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
