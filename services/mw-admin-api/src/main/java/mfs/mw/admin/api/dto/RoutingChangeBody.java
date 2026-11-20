package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** A proposed routing change (ticket 98). {@code action} defaults to UPSERT. */
public record RoutingChangeBody(
    String action,
    Map<String, Object> value,
    @NotBlank @Size(max = 500) String reason) {}
