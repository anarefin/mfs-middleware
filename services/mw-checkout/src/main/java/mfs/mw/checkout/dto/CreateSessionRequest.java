package mfs.mw.checkout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /checkout/v1/sessions} (spec 9.3 step 1; contract {@code
 * api/mw-checkout/checkout.v1.openapi.yaml#/components/schemas/CreateSessionRequest}). The four
 * URLs must be absolute {@code https} URLs without a fragment.
 */
public record CreateSessionRequest(
    @NotBlank @Pattern(regexp = ORDER_ID) String orderId,
    @Valid @NotNull Amount amount,
    @NotBlank @Size(max = 2048) @Pattern(regexp = HTTPS_URL) String successUrl,
    @NotBlank @Size(max = 2048) @Pattern(regexp = HTTPS_URL) String failUrl,
    @NotBlank @Size(max = 2048) @Pattern(regexp = HTTPS_URL) String cancelUrl,
    @NotBlank @Size(max = 2048) @Pattern(regexp = HTTPS_URL) String ipnUrl,
    @Size(max = 140) String description,
    @Pattern(regexp = "bn|en") String locale) {

  public static final String ORDER_ID = "[A-Za-z0-9._:-]{1,64}";
  public static final String HTTPS_URL = "https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\\s#]*)?(\\?[^\\s#]*)?";
}
