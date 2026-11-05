package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Result event from the core on {@code core.wallet.event.v1} (spec 6.3; contract {@code
 * core.event.v1}, owned by mw-core-mock). Unknown fields are ignored; {@code payload} is read per
 * {@code eventType}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreEvent(
    int schemaVersion,
    String eventType,
    String eventId,
    String commandId,
    String txnId,
    String coreRef,
    Instant occurredAt,
    String outcome,
    String reasonCode,
    String reasonMessage,
    JsonNode payload) {}
