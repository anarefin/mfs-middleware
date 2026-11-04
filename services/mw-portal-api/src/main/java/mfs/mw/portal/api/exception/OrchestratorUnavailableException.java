package mfs.mw.portal.api.exception;

/**
 * mw-orchestrator's query API could not be asked or gave no usable answer (ticket 35). Retried
 * and counted by Resilience4j instance {@code orchestrator}; the service answers {@code 503
 * MW-SYS-040}.
 */
public class OrchestratorUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OrchestratorUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
