package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Payload of {@code ReverseTransaction} (contract {@code core.command.v1}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReversePayload(String originalTxnId, Amount amount, Amount fee, String reason) {}
