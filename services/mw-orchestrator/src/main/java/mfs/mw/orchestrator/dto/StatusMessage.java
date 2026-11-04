package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/** A state change on {@code mw.txn.status.v1} (contract {@code mw.txn.status.v1}, owned by this service). */
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
    Instant updatedAt) {}
