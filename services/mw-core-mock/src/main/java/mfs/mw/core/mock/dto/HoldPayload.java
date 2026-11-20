package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Payload of {@code CommitHold} and {@code ReleaseHold} (the internal core command; contract {@code core.command.v1} retired in ticket 95b). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HoldPayload(String holdId, String reason) {}
