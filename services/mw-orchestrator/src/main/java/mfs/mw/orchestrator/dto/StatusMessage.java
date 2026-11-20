package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * A state change on {@code mw.txn.status.v1} (contract {@code mw.txn.status.v1}, owned by this
 * service). {@code balance} only on the COMPLETED status of a BALANCE check (ADR D26).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusMessage(
    int schemaVersion,
    String txnId,
    String idempotencyKey,
    String correlationId,
    String channel,
    String txnType,
    String status,
    String previousStatus,
    Party initiator,
    Party debitParty,
    Party creditParty,
    Amount amount,
    Amount fee,
    PartnerRef partner,
    String partnerRef,
    String coreRef,
    String originalTxnId,
    ErrorInfo error,
    String locale,
    Map<String, String> attributes,
    Instant requestedAt,
    Instant updatedAt,
    Balance balance) {

  /** The same status with other {@code attributes} (the card hosted page session, ticket 118b). */
  public StatusMessage withAttributes(Map<String, String> newAttributes) {
    return new StatusMessage(
        schemaVersion, txnId, idempotencyKey, correlationId, channel, txnType, status, previousStatus, initiator,
        debitParty, creditParty, amount, fee, partner, partnerRef, coreRef, originalTxnId, error, locale,
        newAttributes, requestedAt, updatedAt, balance);
  }
}
