package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * A request on {@code mw.txn.request.v1} (contract {@code mw.txn.request.v1}, owned by this
 * service). Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxnRequest(
    int schemaVersion,
    String txnId,
    String idempotencyKey,
    String correlationId,
    String channel,
    String txnType,
    Party initiator,
    Party debitParty,
    Party creditParty,
    Amount amount,
    PartnerRef partner,
    String originalTxnId,
    EncryptedPin encryptedPin,
    String locale,
    Map<String, String> metadata,
    Map<String, String> attributes,
    Instant requestedAt) {

  /** The request as stored: everything except the PIN ciphertext, which has its own column. */
  public TxnRequest withoutPin() {
    return new TxnRequest(
        schemaVersion, txnId, idempotencyKey, correlationId, channel, txnType, initiator,
        debitParty, creditParty, amount, partner, originalTxnId, null, locale, metadata,
        attributes, requestedAt);
  }
}
