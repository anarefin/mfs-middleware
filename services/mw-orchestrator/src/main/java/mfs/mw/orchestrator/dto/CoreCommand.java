package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Command envelope on {@code core.wallet.command.v1} (spec 6.3; contract {@code core.command.v1},
 * owned by this service). {@code payload} is the per-command record.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoreCommand(
    int schemaVersion,
    String commandType,
    String commandId,
    String txnId,
    String correlationId,
    Instant issuedAt,
    Instant expiresAt,
    Object payload) {}
