package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of {@code ReverseTransaction} (the internal core command; contract {@code core.command.v1} retired in ticket 95b). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReversePayload(String originalTxnId, Amount amount, String reason) {}
