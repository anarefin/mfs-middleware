package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Command envelope on {@code core.wallet.command.v1} (spec 6.3; contract {@code core.command.v1},
 * owned by mw-orchestrator). The payload is read per {@code commandType}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreCommand(
    int schemaVersion,
    String commandType,
    String commandId,
    String txnId,
    String correlationId,
    Instant issuedAt,
    Instant expiresAt,
    JsonNode payload) {}
