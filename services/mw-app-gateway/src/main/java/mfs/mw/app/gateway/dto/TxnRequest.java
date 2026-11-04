package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * The canonical request this service publishes on {@code mw.txn.request.v1} (spec 5.1; contract
 * {@code events/mw-orchestrator/mw.txn.request.v1}, owned by mw-orchestrator).
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
    EncryptedPin encryptedPin,
    String locale,
    Map<String, String> metadata,
    Map<String, String> attributes,
    Instant requestedAt) {}
