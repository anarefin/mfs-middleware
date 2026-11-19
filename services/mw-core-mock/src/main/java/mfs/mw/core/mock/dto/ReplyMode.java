package mfs.mw.core.mock.dto;

/**
 * What happens with the result event of a core command (the core's "callback", ticket 103): published
 * once, twice (same eventId), after a delay, or never (the command still took effect).
 */
public enum ReplyMode {
  ONCE,
  DUPLICATE,
  LATE,
  NONE
}
