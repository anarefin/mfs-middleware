package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /admin/v1/ussd/menus/preview} (ticket 133b): a draft tree walked on one MNO. Checked
 * against the contract first ({@code controller/BodySchemaAdvice}), the tree then by {@code
 * service/MenuValidator}. The answers are the operator's sample inputs: never logged or stored.
 *
 * @param menu the tree: {@code start}, {@code messages}, {@code nodes} (mw-ussd's menu JSON)
 * @param menuId the menu the tree is a draft of (default {@code main})
 * @param language the session language, {@code bn} (default) or {@code en}
 * @param inputs the answers, in order
 * @param outcome what an ACTION reached returns: COMPLETED (default), FAILED or PROCESSING
 */
public record UssdPreviewBody(
    @NotNull Map<String, Object> menu,
    @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,31}$") String menuId,
    @NotBlank @Size(max = 16) String mno,
    @Pattern(regexp = "^(bn|en)$") String language,
    @Size(max = 50) List<@NotNull @Size(max = 64) String> inputs,
    @Pattern(regexp = "^(COMPLETED|FAILED|PROCESSING)$") String outcome) {

  public UssdPreviewBody {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
  }

  @Override
  public String toString() {
    return "UssdPreviewBody[mno=" + mno + ", inputs=" + inputs.size() + "]";
  }
}
