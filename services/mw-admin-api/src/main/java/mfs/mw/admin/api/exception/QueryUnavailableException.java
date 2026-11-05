package mfs.mw.admin.api.exception;

/**
 * mw-orchestrator's or mw-audit-dw's internal query API (or the token endpoint in front of them)
 * could not be asked or gave no usable answer (ticket 36). Retried and counted by Resilience4j
 * instances {@code orchestrator} / {@code audit-dw}; the service answers {@code 503 MW-SYS-040}.
 */
public class QueryUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public QueryUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
