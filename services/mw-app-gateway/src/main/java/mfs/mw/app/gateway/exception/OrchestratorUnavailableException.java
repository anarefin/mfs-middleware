package mfs.mw.app.gateway.exception;

/**
 * mw-orchestrator's query API could not be asked (connect failure, timeout, no service token, an
 * answer other than 200, unreadable body). Retried (an idempotent read) and counted by the circuit
 * breaker; the history answers 503 {@code MW-SYS-013}.
 */
public class OrchestratorUnavailableException extends RuntimeException {

  public OrchestratorUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
