package mfs.mw.admin.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /admin/v1/ussd-menus/{menuId}/change-requests} (ticket 39, spec 9.2, 15.2): a new
 * version of one USSD menu tree and when it goes live on which MNO. The body is checked against
 * the contract first ({@code filter/BodySchemaAdvice}), the tree then by {@code
 * service/MenuValidator}; {@code menuId} and {@code version} inside the tree are set by this
 * service.
 *
 * @param menu the tree: {@code start}, {@code messages}, {@code nodes} (mw-ussd's menu JSON)
 * @param activations per MNO the go-live time (approval time when absent or earlier)
 * @param reason why (free text; never logged, scrubbed in the audit)
 */
public record UssdMenuChangeBody(
    @NotNull Map<String, Object> menu,
    @NotEmpty @Size(max = 16) List<@Valid @NotNull Activation> activations,
    @NotBlank @Size(max = 500) String reason) {

  public UssdMenuChangeBody {
    activations = activations == null ? null : List.copyOf(activations);
  }

  /** One MNO's go-live of the version. */
  public record Activation(@NotBlank String mno, Instant goLiveAt) {}
}
