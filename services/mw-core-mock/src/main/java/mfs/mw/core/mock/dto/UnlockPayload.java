package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of {@code UnlockWallet} (the internal core command; contract {@code core.command.v1} retired in ticket 95b, ticket 52): the PIN-locked
 * wallet ops unlock after maker-checker approval in mw-admin-api. No PIN.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnlockPayload(Party subject, String changeRequestId, String makerId, String checkerId) {}
