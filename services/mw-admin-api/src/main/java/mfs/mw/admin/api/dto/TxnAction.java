package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * This service's own type for {@code mw.txn.action.v1} (contract owned by this service, ticket 37):
 * an approved operator action for mw-orchestrator. {@code reason} is the maker's free text, scrubbed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TxnAction(
    int schemaVersion,
    String actionId,
    String txnId,
    String action,
    String correlationId,
    String changeRequestId,
    String makerId,
    String checkerId,
    String reason,
    Instant requestedAt) {}
