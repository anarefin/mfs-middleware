package mfs.mw.seeder.exception;

import org.springframework.boot.ExitCodeGenerator;

/** A seed step failed or timed out: the run stops and the JVM exits 1. */
public class SeedFailedException extends RuntimeException implements ExitCodeGenerator {

  private final String stepId;

  public SeedFailedException(String stepId, String reason, Throwable cause) {
    super("seed step " + stepId + " failed: " + reason, cause);
    this.stepId = stepId;
  }

  public String stepId() {
    return stepId;
  }

  @Override
  public int getExitCode() {
    return 1;
  }
}
