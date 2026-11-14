package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * Payload of {@code ValidateTransaction}, {@code PostTransfer} and {@code HoldFunds} (contract
 * {@code core.command.v1}). The fee the orchestrator may send is ignored: the core decides the fee.
 * The customer's authorisation is mw-auth's {@code assertion} (with the request's {@code
 * idempotencyKey}, part of its txnHash; ticket 71) or, until ticket 72, the deprecated {@code
 * encryptedPin}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferPayload(
    String txnType,
    Party debitParty,
    Party creditParty,
    Amount amount,
    @Sensitive(SensitiveType.SECRET) String assertion,
    String idempotencyKey,
    EncryptedPin encryptedPin) {

  /**
   * The org bulk payout child (ticket 34): the org authorised the batch by maker-checker in the
   * org portal, so the contract carries no PIN for it.
   */
  public static final String BULK_DISBURSE = "BULK_DISBURSE";

  /** Whether this transfer needs the customer's authorisation (contract: every type but BULK_DISBURSE). */
  public boolean pinRequired() {
    return !BULK_DISBURSE.equals(txnType);
  }
}
