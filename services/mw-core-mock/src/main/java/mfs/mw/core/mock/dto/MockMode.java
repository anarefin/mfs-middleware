package mfs.mw.core.mock.dto;

/**
 * The outcome of one call of the core party as the mock-control engine decided it (ticket 103; the
 * core's copy of mw-simulators' {@code SimMode}). The core has no magic values: without a scenario
 * every call is SUCCESS, i.e. decided by the ledger rules alone.
 */
public enum MockMode {
  SUCCESS,
  BUSINESS_FAILURE,
  TIMEOUT,
  SERVER_ERROR_AFTER_SEND,
  MALFORMED,
  CONNECTION_RESET,
  PARTIAL_SUCCESS
}
