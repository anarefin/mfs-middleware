package mfs.mw.app.gateway.entity;

import java.time.Instant;

/**
 * A row of {@code app_device}: a device bound to a wallet with its P-256 public key (base64 X.509).
 * {@code active} is false once another device replaced it (single active device per wallet).
 */
public record AppDevice(
    String walletId, String deviceId, String subject, String publicKey, boolean active, Instant boundAt) {}
