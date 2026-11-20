package mfs.mw.simulators.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /mock-control/{party}/run} of a file mock (ticket 123).
 *
 * @param operation trustbank mt940 (default) or mt942; aml respond; dw validate
 * @param date trustbank: the business day (Asia/Dhaka; mt940 default yesterday, mt942 today)
 * @param at trustbank mt942: the cut time HH:mm (default now)
 */
public record FileRunRequest(
    @Size(max = 64) String operation, LocalDate date, @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String at) {}
