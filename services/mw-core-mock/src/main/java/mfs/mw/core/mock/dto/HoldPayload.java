package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of {@code CommitHold} and {@code ReleaseHold} (contract {@code core.command.v1}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HoldPayload(String holdId, String reason) {}
