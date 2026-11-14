package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.orchestrator.util.Sensitive;
import mfs.mw.orchestrator.util.SensitiveType;

/**
 * Payload of {@code ValidateTransaction}, {@code PostTransfer} and {@code HoldFunds} (contract
 * {@code core.command.v1}). The core decides the fee, so none is sent. The customer's authorisation
 * is mw-auth's {@code assertion} with the request's {@code idempotencyKey} (part of its txnHash;
 * spec 18.7, D22); no PIN travels to the core (ticket 72). A request without an assertion (a bulk
 * child) sends neither.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferPayload(
    String txnType,
    Party debitParty,
    Party creditParty,
    Amount amount,
    @Sensitive(SensitiveType.SECRET) String assertion,
    String idempotencyKey) {

  /** The payload for a request, with its assertion and idempotency key when it has one. */
  public static TransferPayload of(TxnRequest req) {
    return new TransferPayload(req.txnType(), req.debitParty(), req.creditParty(), req.amount(),
        req.assertion(), req.assertion() == null ? null : req.idempotencyKey());
  }
}
