package mfs.mw.audit.dw.exception;

/**
 * mw-crypto (or the Keycloak token endpoint in front of it) could not sign or answer (ticket 74):
 * {@code MW-SYS-002}, retryable. Never carries a secret or a response body.
 */
public class CryptoUnavailableException extends MwException {

  private static final long serialVersionUID = 1L;

  public CryptoUnavailableException(String message, Throwable cause) {
    super(ErrorCodes.SYS_UNAVAILABLE, message, cause);
  }
}
