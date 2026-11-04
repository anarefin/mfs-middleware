package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of {@code ReverseTransaction} (contract {@code core.command.v1}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReversePayload(String originalTxnId, Amount amount, String reason) {}
