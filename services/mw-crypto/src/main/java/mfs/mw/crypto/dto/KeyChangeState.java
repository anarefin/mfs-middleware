package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * {@code GET /internal/v1/key-changes/{changeId}} and {@code POST .../cancel} (ticket 75): whether
 * that approval attempt was applied (then its action, keyId and the key's ACTIVE kid now) or
 * cancelled (terminal: a later PUT of the id is refused); neither when nothing happened yet (GET only).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeyChangeState(
    UUID changeId, boolean applied, boolean cancelled, String action, String keyId, String kid) {}
