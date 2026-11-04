package mfs.mw.simulators.dto;

/**
 * Misbehaviour of the SMPP USSD gateway simulator (ticket 27); a posted scenario replaces the
 * previous one ({@code {}} resets).
 *
 * @param throttleNext the next n submit_sm are refused with 0x58 (throttled)
 * @param queueFullNext the next n submit_sm are refused with 0x14 (message queue full)
 * @param genericNackNext the next n submit_sm are answered with a generic_nack
 * @param silentEnquireLink stop answering enquire_link (the binds then look dead)
 * @param screenTimeoutMs how long a played step waits for the screen (default from config)
 */
public record UssdSmppScenario(
    Integer throttleNext, Integer queueFullNext, Integer genericNackNext, Boolean silentEnquireLink,
    Long screenTimeoutMs) {}
