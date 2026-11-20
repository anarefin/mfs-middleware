package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code auth.wallet-lock.v1} (owned by mw-auth) as this service reads it (ticket 111): a LOCK or
 * UNLOCK notice. Other actions are ignored. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletLockEvent(
    int schemaVersion,
    String commandId,
    String action,
    String walletId,
    String reason,
    String correlationId) {}
