package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * {@code GET /internal/v1/key-changes/{changeRequestId}} (ticket 75): whether that approved change
 * was applied; when it was, its action, keyId and the key's ACTIVE kid now.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeyChangeState(UUID changeRequestId, boolean applied, String action, String keyId, String kid) {}
