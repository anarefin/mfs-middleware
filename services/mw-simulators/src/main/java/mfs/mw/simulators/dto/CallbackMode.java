package mfs.mw.simulators.dto;

/**
 * What a simulator does with its callback after applying a request: none, one, the same one twice,
 * one after {@code callbackDelayMs}, or the outcome followed by a stale PENDING (out of order;
 * partners that report PENDING, billagg).
 */
public enum CallbackMode {
  NONE,
  ONCE,
  DUPLICATE,
  LATE,
  OUT_OF_ORDER
}
