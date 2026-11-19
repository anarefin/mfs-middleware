package mfs.mw.simulators.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Optional body of {@code POST /mock-control/reset} (ticket 103): {@code seedVersion} is passed on to
 * mw-core-mock (another folder of its seed files); absent = the version in force there.
 */
public record ResetRequest(@Pattern(regexp = "[a-z0-9][a-z0-9-]{0,31}") String seedVersion) {}
