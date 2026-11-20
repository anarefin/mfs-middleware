package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * The core's internal command (spec 6.3): what a {@code /core/v1} money call is carried out as
 * ({@code service/CoreWalletService}; ticket 95b retired the Kafka contract core.command.v1 and its
 * topics). The payload is read per {@code commandType}.
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
