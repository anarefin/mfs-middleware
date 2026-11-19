package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * Payload of {@code ValidateTransaction}, {@code PostTransfer} and {@code HoldFunds} (contract
 * {@code core.command.v1}). The fee the orchestrator may send is ignored: the core decides the fee.
 * The customer's authorisation is mw-auth's {@code assertion} with the request's {@code
 * idempotencyKey} (part of its txnHash; ticket 71). The core holds and checks no PIN (ticket 72); a
 * stray {@code encryptedPin} is ignored as an unknown field.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferPayload(
    String txnType,
    Party debitParty,
    Party creditParty,
    Amount amount,
    @Sensitive(SensitiveType.SECRET) String assertion,
    String idempotencyKey,
    Map<String, String> attributes) {

  /**
   * The {@code attributes} key naming the settlement wallet a hold's commit credits (ticket 94,
   * WAL-07: {@code SETTLEMENT_OCS_GP}, ...); absent = {@code SETTLEMENT}.
   */
  public static final String SETTLEMENT_WALLET = "settlementWallet";

  /** The settlement wallet named in {@code attributes}, or null. */
  public String settlementWallet() {
    return attributes == null ? null : attributes.get(SETTLEMENT_WALLET);
  }

  /**
   * The org bulk payout child (ticket 34): the org authorised the batch by maker-checker in the
   * org portal, so the contract carries no assertion for it.
   */
  public static final String BULK_DISBURSE = "BULK_DISBURSE";

  /** Whether this transfer needs the customer's authorisation (contract: every type but BULK_DISBURSE). */
  public boolean authorisationRequired() {
    return !BULK_DISBURSE.equals(txnType);
  }
}
