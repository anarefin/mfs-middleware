package mfs.mw.auth.dto;

import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * {@code GET /internal/v1/sessions/{walletId}/{deviceId}} (ticket 68): the wallet's live session on
 * the device, just extended by its idle timeout.
 */
public record LiveSession(
    @Sensitive(SensitiveType.MSISDN) String walletId, String deviceId, long idleTimeoutSeconds) {}
