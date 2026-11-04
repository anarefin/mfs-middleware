package mfs.mw.portal.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * A bulk child as published on {@code mw.txn.request.v1} (spec 5.1; contract {@code
 * events/mw-orchestrator/mw.txn.request.v1}, owned by mw-orchestrator). No PIN: the org authorised
 * the batch by maker-checker.
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
    String locale,
    Map<String, String> attributes,
    Instant requestedAt) {}
