package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/**
 * A state change read from {@code mw.txn.status.v1} (contract {@code
 * events/mw-orchestrator/mw.txn.status.v1}, owned by mw-orchestrator): only the fields the rules
 * and templates use; unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TxnStatusEvent(
    String txnId,
    String correlationId,
    String channel,
    String txnType,
    String status,
    Party initiator,
    Party debitParty,
    Party creditParty,
    Amount amount,
    Amount fee,
    String coreRef,
    String partnerRef,
    String locale,
    PartnerRef partner,
    TxnError error) {

  /** The partner of a partner flow (TOPUP, BILL_PAY, ADD_MONEY_BANK). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PartnerRef(String partnerId, String productCode, String billerCode) {}

  /** The error of a FAILED / REJECTED / MANUAL_REVIEW state: its code only. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TxnError(String code) {}

  /** Party (spec 5.1); the id is masked by type in logs. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Party(String type, @Sensitive(SensitiveType.PARTY_ID) String id, String walletType) {}

  /** Minor units (poisha) and currency. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Amount(long valueMinor, String currency) {}
}
