package mfs.mw.notify.exception;

/** Chassis error codes (spec 5.3). Service-specific codes go next to these. */
public final class ErrorCodes {

  /** Bean Validation failed on a body, parameter or path variable. */
  public static final String VAL_INVALID = "MW-VAL-001";
  /** Body missing or not parseable. */
  public static final String VAL_MALFORMED = "MW-VAL-002";
  /** No such endpoint or resource. */
  public static final String VAL_NOT_FOUND = "MW-VAL-003";
  /** HTTP method not allowed on this endpoint. */
  public static final String VAL_METHOD = "MW-VAL-004";
  /** Content type not supported. */
  public static final String VAL_MEDIA_TYPE = "MW-VAL-005";
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";

  // mw-notify (spec 10): codes carried on mw.notify.status.v1 errorCode.
  /** A requested channel without its address (SMS: MSISDN, EMAIL: email) or not built (PUSH). */
  public static final String NOTIFY_NO_CHANNEL = "MW-VAL-101";
  /** Template missing for code/channel/locale, or a placeholder without a param. */
  public static final String NOTIFY_TEMPLATE = "MW-VAL-102";
  /** The SMSC (submit_sm) or the SMTP server (5xx) refused the message for good. */
  public static final String NOTIFY_SMSC_REJECTED = "MW-SYS-101";
  /** Transient SMPP / SMTP errors on every attempt (spec 10.4: 3 retries, 2/10/30 s). */
  public static final String NOTIFY_RETRIES_EXHAUSTED = "MW-SYS-102";
  /** The SMSC reported the message undeliverable (DLR UNDELIV/REJECTD/DELETED). */
  public static final String NOTIFY_UNDELIVERABLE = "MW-SYS-103";
  /** A transient SMPP / SMTP error; another attempt is scheduled (status FAILED, final=false). */
  public static final String NOTIFY_SMSC_TRANSIENT = "MW-SYS-104";

  private ErrorCodes() {}
}
