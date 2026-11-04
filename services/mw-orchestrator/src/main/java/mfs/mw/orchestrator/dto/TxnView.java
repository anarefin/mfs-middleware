package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * One transaction as the internal query API shows it (ticket 35, contract {@code
 * txn-query.v1.openapi.yaml}). Customer data is already masked (spec 18.3): party ids by type,
 * names to the initial; no PIN, no idempotency key, no raw request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxnView(
    String txnId,
    String txnType,
    String channel,
    String status,
    String previousStatus,
    Instant createdAt,
    Instant updatedAt,
    String correlationId,
    Amount amount,
    Amount fee,
    Party initiator,
    Party debitParty,
    Party creditParty,
    PartnerRef partner,
    String partnerRef,
    String coreRef,
    String originalTxnId,
    String reversalTxnId,
    ErrorInfo error,
    Map<String, String> attributes) {}
