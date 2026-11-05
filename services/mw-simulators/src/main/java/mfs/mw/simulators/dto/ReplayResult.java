package mfs.mw.simulators.dto;

/** Outcome of a replay: the HTTP status the callback target answered, -1 if unreachable. */
public record ReplayResult(String reference, int targetStatus) {}
