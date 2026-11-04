package mfs.mw.simulators.dto;

/**
 * State of the SMPP USSD gateway simulator's binds.
 *
 * @param bound sessions bound right now
 * @param binds bind requests accepted since start (re-binds count again)
 * @param enquireLinks enquire_link received since start
 * @param refused submit_sm refused since start (0x58, 0x14, generic_nack)
 */
public record UssdSmppBinds(int bound, int binds, int enquireLinks, int refused) {}
