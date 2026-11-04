package mfs.mw.simulators.dto;

/** Final {@code stat:} values the SMSC simulator puts in its delivery receipts (SMPP 3.4 App. B). */
public enum SmscDlrState {
  DELIVRD,
  EXPIRED,
  UNDELIV
}
