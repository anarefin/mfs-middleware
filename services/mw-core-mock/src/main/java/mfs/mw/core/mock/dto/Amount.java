package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Money on the wire: non-negative integer minor units, currency BDT (contract {@code Amount}). The
 * constraints apply where a REST body is {@code @Valid} (core-wallet.v1); a command is not validated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Amount(@PositiveOrZero long valueMinor, @NotNull @Pattern(regexp = "^BDT$") String currency) {

  public static final String BDT = "BDT";

  public static Amount bdt(long valueMinor) {
    return new Amount(valueMinor, BDT);
  }
}
