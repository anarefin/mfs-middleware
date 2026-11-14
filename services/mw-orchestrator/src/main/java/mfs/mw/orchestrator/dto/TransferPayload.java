package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.orchestrator.util.Sensitive;
import mfs.mw.orchestrator.util.SensitiveType;

/**
 * Payload of {@code ValidateTransaction}, {@code PostTransfer} and {@code HoldFunds} (contract
 * {@code core.command.v1}). The core decides the fee, so none is sent. The customer's authorisation
 * is mw-auth's {@code assertion} with the request's {@code idempotencyKey} (part of its txnHash;
 * spec 18.7, D22) or, until ticket 72, the deprecated {@code encryptedPin}: never both.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferPayload(
    String txnType,
    Party debitParty,
    Party creditParty,
    Amount amount,
    @Sensitive(SensitiveType.SECRET) String assertion,
    String idempotencyKey,
    EncryptedPin encryptedPin) {

  /** The payload for a request: its assertion when it has one, else its PIN ciphertext. */
  public static TransferPayload of(TxnRequest req, EncryptedPin storedPin) {
    if (req.assertion() != null) {
      return new TransferPayload(req.txnType(), req.debitParty(), req.creditParty(), req.amount(),
          req.assertion(), req.idempotencyKey(), null);
    }
    return new TransferPayload(req.txnType(), req.debitParty(), req.creditParty(), req.amount(), null, null, storedPin);
  }
}
