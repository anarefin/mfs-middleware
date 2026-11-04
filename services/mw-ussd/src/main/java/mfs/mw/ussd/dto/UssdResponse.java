package mfs.mw.ussd.dto;

/** The next screen and whether the session ends with it. */
public record UssdResponse(String sessionId, String text, boolean end) {}
