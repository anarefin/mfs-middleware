package mfs.mw.adapter.atm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This adapter's own type for {@code mw.partner.inbound.v1} (contract owned by mw-orchestrator):
 * an authenticated, deduplicated ATM call (family {@code atm}, kinds {@code ATM_VERIFY_DEBIT},
 * {@code ATM_CONFIRM_DISPENSE}, {@code ATM_REVERSAL_ADVICE}, {@code ATM_CASH_IN}; ticket 120a).
 * The customer's wallet is the credit party (cash-in) or the debit party (cash-out). The ATM token
 * is never carried (FL-11): its check is ticket 120b's.
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
