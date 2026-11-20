package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /admin/v1/partners/{partnerId}/error-map/change-requests} (ticket 131, FRS ADM-08):
 * replace a partner's partner-code -> canonical-code map ({@code PartnerConfig.errorMap}) and,
 * optionally, its ambiguous codes (absent: kept). The rest of the partner's config stays as it is.
 */
public record ErrorMapChangeBody(
    @NotNull ErrorMapAction action,
    @NotBlank @Size(max = 500) String reason,
    @NotNull Map<String, PartnerConfigValue.ErrorMapping> errorMap,
    List<String> ambiguousCodes) {

  /** The only error-map change: the full map replaces the held one. */
  public enum ErrorMapAction {
    SET_ERROR_MAP
  }
}
