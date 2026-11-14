package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** A PIN reset as the app sees it ({@code auth.v1} PinReset, ticket 70). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PinResetView(String resetId, String kind, String status, Instant expiresAt, String reason) {}
