package mfs.mw.auth.dto;

import java.time.Instant;

/** Answer of a device binding: the device, the wallet (masked) and when it was bound. */
public record DeviceBound(String deviceId, String wallet, Instant boundAt) {}
