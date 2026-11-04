package mfs.mw.simulators.dto;

/**
 * One answer of mw-ussd as the simulated MNO gateway received it.
 *
 * @param status HTTP status of the step (-1: unreachable)
 * @param message the screen text
 * @param flag the continue/end flag
 */
public record UssdScreen(int status, String message, String flag) {}
