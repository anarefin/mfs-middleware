package mfs.mw.orchestrator.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Test-only API (ticket 99): the business-duplicate window in whole seconds, 0 = off (contract
 * {@code orchestrator-test.v1} {@code DuplicateGuardWindow}).
 */
public record DuplicateGuardWindow(@NotNull @Min(0) @Max(3600) Integer windowSeconds) {}
