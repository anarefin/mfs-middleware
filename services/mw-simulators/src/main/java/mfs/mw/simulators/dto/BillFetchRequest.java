package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Bill fetch request of the generic aggregator contract. */
public record BillFetchRequest(
    @NotBlank @Pattern(regexp = "^[A-Z0-9_]{1,32}$") String billerCode,
    @NotBlank @Pattern(regexp = "^[0-9A-Za-z-]{1,32}$") String billNumber) {}
