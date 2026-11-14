package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/tokens}: deterministic tokens of up to {@link #MAX_BATCH} values of one
 * domain. The values are tokenised exactly as given (the caller normalises, e.g. an MSISDN to {@code
 * 01XXXXXXXXX}).
 */
public record TokeniseRequest(
    @NotNull Domain domain,
    @NotEmpty @Size(max = MAX_BATCH) @Sensitive(SensitiveType.SECRET)
        List<@NotBlank @Size(max = 64) String> values) {

  public static final int MAX_BATCH = 500;

  /** The tokenisation domains, one {@code TOKENISE_<domain>} key each. */
  public enum Domain {
    MSISDN,
    NID,
    ACCOUNT
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
