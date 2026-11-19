package mfs.mw.simulators.dto;

/** The outcomes a simulated partner call can take (tickets 15, 21; spec 11.3). */
public enum SimMode {
  SUCCESS,
  BUSINESS_FAILURE,
  /** Top-up applied, reply held past the caller's time limit. */
  TIMEOUT,
  /** Top-up applied, then HTTP 500. */
  SERVER_ERROR_AFTER_SEND,
  /** Failure with a code no error map knows. */
  UNKNOWN_CODE,
  /** Top-up applied, reply body truncated (not valid JSON). */
  MALFORMED,
  /** Applied, the connection closed without an answer (mock control CONNECTION_RESET, ticket 102). */
  CONNECTION_RESET,
  /** Partly applied: the party's partial reply, status PARTIAL (mock control PARTIAL_SUCCESS). */
  PARTIAL_SUCCESS
}
