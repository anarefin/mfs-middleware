package mfs.mw.core.mock.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Optional body of {@code POST /mock-control/reset}: {@code seedVersion} reloads another folder of seed
 * files ({@code seed/<version>/}); absent = the version in force (on start {@code
 * mw.coremock.seed.version}).
 */
public record ResetRequest(@Pattern(regexp = "[a-z0-9][a-z0-9-]{0,31}") String seedVersion) {}
