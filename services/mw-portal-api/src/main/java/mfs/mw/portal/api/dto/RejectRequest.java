package mfs.mw.portal.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST .../batches/{batchId}/reject}: why the checker refused the batch. */
public record RejectRequest(@NotBlank @Size(max = 200) String reason) {}
