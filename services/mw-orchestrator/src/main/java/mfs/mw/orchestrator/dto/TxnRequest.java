package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import mfs.mw.orchestrator.util.Sensitive;
import mfs.mw.orchestrator.util.SensitiveType;

/**
 * A request on {@code mw.txn.request.v1} (contract {@code mw.txn.request.v1}, owned by this
 * service). Unknown fields are ignored. {@code assertion} is mw-auth's transaction authorisation
 * (spec 18.7, D22): no PIN material, so it is stored with the request (PostTransfer and HoldFunds
 * carry it after ValidateTransaction). Since ticket 72 no PIN ciphertext reaches this service; an
 * old channel's {@code encryptedPin} would be ignored as an unknown field.
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
    @Sensitive(SensitiveType.SECRET) String assertion,
    String locale,
    Map<String, String> metadata,
    Map<String, String> attributes,
    Instant requestedAt) {

  /** A request without an assertion (bulk children, reversals). */
  public TxnRequest(
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
      String locale,
      Map<String, String> metadata,
      Map<String, String> attributes,
      Instant requestedAt) {
    this(schemaVersion, txnId, idempotencyKey, correlationId, channel, txnType, initiator, debitParty,
        creditParty, amount, partner, originalTxnId, null, locale, metadata, attributes, requestedAt);
  }
}
