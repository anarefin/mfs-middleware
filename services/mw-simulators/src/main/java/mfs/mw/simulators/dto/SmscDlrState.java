package mfs.mw.simulators.dto;

/**
 * {@code stat:} values the SMSC simulator puts in its delivery receipts (SMPP 3.4 App. B): the final
 * ones, and ENROUTE for the stale receipt of an OUT_OF_ORDER_CALLBACK scenario (first, so a final state
 * always ranks above it).
 */
public enum SmscDlrState {
  ENROUTE,
  DELIVRD,
  EXPIRED,
  UNDELIV
}
