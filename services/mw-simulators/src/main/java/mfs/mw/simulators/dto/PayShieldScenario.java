package mfs.mw.simulators.dto;

import java.util.List;

/**
 * Misbehaviour of the payShield simulator (ticket 64), {@code POST /sim/payshield/scenario}; a posted
 * scenario replaces the previous one ({@code {}} or DELETE resets).
 *
 * @param latencyMs wait this long before every answer
 * @param errorCode answer this payShield error code (two characters, e.g. {@code 15}, {@code 68})
 *     instead of running the command
 * @param outage close every connection without an answer (a dead HSM)
 * @param commands the command codes {@code errorCode} applies to (none: every command)
 */
public record PayShieldScenario(Long latencyMs, String errorCode, Boolean outage, List<String> commands) {

  public PayShieldScenario {
    commands = commands == null ? List.of() : List.copyOf(commands);
  }

  /** The error code forced on {@code command}, or null. */
  public String errorFor(String command) {
    return errorCode != null && (commands.isEmpty() || commands.contains(command)) ? errorCode : null;
  }

  public boolean isOutage() {
    return Boolean.TRUE.equals(outage);
  }

  public long latency() {
    return latencyMs == null ? 0 : latencyMs;
  }
}
