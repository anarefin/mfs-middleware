package mfs.mw.orchestrator.service.saga;

/** One step of the machine: {@code from} to {@code to}, and the effect to perform. */
public record Transition(Status from, Status to, Effect effect) {

  /** False for a retry in place (for example COMMITTING re-sending CommitHold). */
  public boolean changesState() {
    return from != to;
  }
}
