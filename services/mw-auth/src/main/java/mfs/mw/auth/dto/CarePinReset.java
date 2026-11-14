package mfs.mw.auth.dto;

import java.time.Instant;

/** Answer of {@code POST /internal/v1/pin-resets} (auth-internal.v1 CarePinReset, ticket 70). */
public record CarePinReset(String resetId, String status, Instant expiresAt) {}
