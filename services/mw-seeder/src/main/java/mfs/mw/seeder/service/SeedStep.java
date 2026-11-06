package mfs.mw.seeder.service;

/**
 * One idempotent seed step (ticket 57). It looks up before it creates, so running it again changes
 * nothing. A {@link mfs.mw.seeder.exception.RemoteCallException} that is {@code retryable()} makes
 * the runner run the whole step again after a back-off, until the step's timeout; any other
 * exception fails the run.
 */
public interface SeedStep {

  /** Stable id, used in logs, the timeout settings ({@code mw.seeder.step-timeouts.<id>}) and the order. */
  String id();

  StepResult run(SeedContext ctx);
}
