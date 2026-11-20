package mfs.mw.adapter.atm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * Proposed shape of an ATM call on {@code mw.partner.inbound.v1} (contract owned by mw-orchestrator).
 * Not published: that schema's kinds are only CALLBACK, REMITTANCE and NPSB_CREDIT, and it has no
 * debit party, so {@code ATM_VERIFY_DEBIT}, {@code ATM_CONFIRM_DISPENSE}, {@code ATM_REVERSAL_ADVICE}
 * and {@code ATM_CASH_IN} do not fit (ticket 120a). Ticket 120b extends the contract and publishes.
 * The ATM token is never carried (FL-11).
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
      String txnType,
      Amount amount,
      Party debitParty,
      Party creditParty,
      Map<String, String> attributes) {

    public Payload {
      attributes = attributes == null || attributes.isEmpty() ? null : Map.copyOf(attributes);
    }
  }

  public record Amount(long valueMinor, String currency) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Party(String type, String id, String walletType) {}
}
