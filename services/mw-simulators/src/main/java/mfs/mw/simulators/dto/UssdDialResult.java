package mfs.mw.simulators.dto;

import java.util.List;

/**
 * The screens of a simulated session, in order (the dial-in answer first).
 *
 * @param ended true when mw-ussd ended the session (end flag)
 */
public record UssdDialResult(String sessionId, List<UssdScreen> screens, boolean ended) {}
