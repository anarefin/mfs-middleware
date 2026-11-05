package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Money: integer minor units (poisha), currency BDT (contract {@code Amount}). The bean
 * constraints apply where an app sends it: a transaction amount must be greater than zero (the
 * contract itself allows 0).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Amount(
    @NotNull @Positive Long valueMinor, @NotNull @Pattern(regexp = "BDT") String currency) {

  public static final String BDT = "BDT";
}
