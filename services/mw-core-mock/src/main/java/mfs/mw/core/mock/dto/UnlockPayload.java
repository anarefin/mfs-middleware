package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of {@code UnlockWallet} (contract {@code core.command.v1}, ticket 52): the PIN-locked
 * wallet ops unlock after maker-checker approval in mw-admin-api. No PIN.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnlockPayload(Party subject, String changeRequestId, String makerId, String checkerId) {}
