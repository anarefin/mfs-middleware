package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * A proposed biller catalogue change (ticket 113). {@code action} UPSERT (default: create or edit,
 * {@code value} the full {@code BillerValue} without {@code code}), ENABLE or DISABLE (the current
 * entry with {@code enabled} flipped; DISABLE takes the bn/en maintenance text, else the entry's).
 */
public record BillerChangeBody(
    String action,
    Map<String, Object> value,
    @Size(max = 500) String maintenanceEn,
    @Size(max = 500) String maintenanceBn,
    @NotBlank @Size(max = 500) String reason) {}
