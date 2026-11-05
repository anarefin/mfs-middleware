package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /admin/v1/change-requests/{id}/reject} (ticket 37): the checker's reason (free text, never logged). */
public record RejectBody(@NotBlank @Size(max = 500) String reason) {}
