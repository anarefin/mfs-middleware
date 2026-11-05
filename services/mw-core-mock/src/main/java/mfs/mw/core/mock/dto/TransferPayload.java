package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of {@code ValidateTransaction} and {@code PostTransfer} (contract {@code
 * core.command.v1}). The fee the orchestrator may send is ignored: the core decides the fee.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferPayload(
    String txnType, Party debitParty, Party creditParty, Amount amount, EncryptedPin encryptedPin) {

  /**
   * The org bulk payout child (ticket 34): the org authorised the batch by maker-checker in the
   * org portal, so the contract carries no PIN for it.
   */
  public static final String BULK_DISBURSE = "BULK_DISBURSE";

  /** Whether this transfer is authorised by the payer's PIN (contract: every type but BULK_DISBURSE). */
  public boolean pinRequired() {
    return !BULK_DISBURSE.equals(txnType);
  }
}
