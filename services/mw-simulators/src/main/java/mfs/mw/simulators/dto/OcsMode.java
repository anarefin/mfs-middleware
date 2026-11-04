package mfs.mw.simulators.dto;

/** The OCS outcomes a test can reach (ticket 15, spec 11.3). */
public enum OcsMode {
  SUCCESS,
  BUSINESS_FAILURE,
  /** Top-up applied, reply held past the caller's time limit. */
  TIMEOUT,
  /** Top-up applied, then HTTP 500. */
  SERVER_ERROR_AFTER_SEND,
  /** Failure with a code no error map knows. */
  UNKNOWN_CODE,
  /** Top-up applied, reply body truncated (not valid JSON). */
  MALFORMED
}
