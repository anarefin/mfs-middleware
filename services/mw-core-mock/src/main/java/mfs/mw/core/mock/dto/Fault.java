package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An injected fault (admin API, test double only). It applies to commands whose type and txnId
 * match ({@code null} matches any), at most {@code remaining} more times when set.
 *
 * @param mode what goes wrong
 * @param commandType command type it applies to, or {@code null} for all
 * @param txnId transaction it applies to, or {@code null} for all
 * @param delayMs DELAY: how long the reply is held back before it is published
 * @param reasonCode REJECT: the core reason of the failure event
 * @param remaining how many more commands it applies to, or {@code null} until reset
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Fault(
    String id, FaultMode mode, String commandType, String txnId, Long delayMs, String reasonCode, Integer remaining) {

  /** Fault modes (ticket 14): the command is still decided consistently, only the outcome travels badly. */
  public enum FaultMode {
    /** The reply is published after {@code delayMs}. */
    DELAY,
    /** The command takes effect but its reply is never published (QueryTransaction still knows). */
    DROP_REPLY,
    /** The reply is published twice (same eventId). */
    DUPLICATE_REPLY,
    /** The command is refused without effect, with a failure event carrying {@code reasonCode}. */
    REJECT
  }

  public boolean matches(String type, String txn) {
    return (commandType == null || commandType.equals(type)) && (txnId == null || txnId.equals(txn));
  }
}
