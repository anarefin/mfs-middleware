package mfs.mw.ussd.dto;

/**
 * The answer to an MNO step: the next screen and the MNO's continue/end flag (per-MNO mapping,
 * {@code mw.ussd.mno.<mno>.response-flags}).
 */
public record HttpUssdResponse(String sessionId, String message, String flag) {}
