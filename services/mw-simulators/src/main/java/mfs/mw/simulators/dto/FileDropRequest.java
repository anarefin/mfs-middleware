package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /mock-control/{party}/files} (ticket 123, test helper): a file put into a file mock's inbox ({@code
 * aml/in}, {@code dw/in}) as the middleware would upload it.
 *
 * @param name a plain file name, no folder
 * @param content the text (UTF-8)
 */
public record FileDropRequest(
    @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String name,
    @NotNull @Size(max = 1_048_576) String content) {}
