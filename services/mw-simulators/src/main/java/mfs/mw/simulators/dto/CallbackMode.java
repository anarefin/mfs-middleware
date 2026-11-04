package mfs.mw.simulators.dto;

/** What a simulator does with its callback after applying a request. */
public enum CallbackMode {
  NONE,
  ONCE,
  DUPLICATE,
  LATE
}
