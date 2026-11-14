package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * The canonical request this service publishes on {@code mw.txn.request.v1} (spec 5.1; contract
 * {@code events/mw-orchestrator/mw.txn.request.v1}, owned by mw-orchestrator). The customer's
 * authorisation is mw-auth's {@code assertion} (ticket 72, D22), bound to this request's values.
 */
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
    @Sensitive(SensitiveType.SECRET) String assertion,
    String locale,
    Map<String, String> metadata,
    Map<String, String> attributes,
    Instant requestedAt) {

  /** The same request carrying mw-auth's assertion. */
  public TxnRequest withAssertion(String jws) {
    return new TxnRequest(
        schemaVersion, txnId, idempotencyKey, correlationId, channel, txnType, initiator, debitParty, creditParty,
        amount, partner, jws, locale, metadata, attributes, requestedAt);
  }
}
