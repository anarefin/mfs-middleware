package mfs.mw.app.gateway.client;

/**
 * The core wallet API could not be asked (connect failure, timeout, no service token, 5xx, an
 * unreadable body). Retried (an idempotent read) and counted by the circuit breaker; the app
 * answers 503 {@code MW-SYS-015}.
 */
public class CoreUnavailableException extends RuntimeException {

  public CoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
