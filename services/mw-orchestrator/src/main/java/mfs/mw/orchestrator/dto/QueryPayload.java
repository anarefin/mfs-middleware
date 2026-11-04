package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload of {@code QueryTransaction} (contract {@code core.command.v1}). Without {@code
 * queryCommandId} the core reports the transaction's state (for a hold: HELD, COMMITTED, RELEASED).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryPayload(String queryCommandId) {}
