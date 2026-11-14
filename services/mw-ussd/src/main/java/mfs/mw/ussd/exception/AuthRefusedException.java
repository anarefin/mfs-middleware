package mfs.mw.ussd.exception;

import mfs.mw.ussd.dto.ErrorEnvelope;

/**
 * mw-auth answered a transaction authorisation with its error envelope (wrong PIN, PIN or wallet
 * locked, no PIN, idempotency conflict, PIN block refused): a definite answer, not a
 * circuit-breaker failure (ticket 72).
 */
public class AuthRefusedException extends RuntimeException {

  private final int status;
  private final transient ErrorEnvelope envelope;

  public AuthRefusedException(int status, ErrorEnvelope envelope) {
    super("mw-auth answered " + status + " " + envelope.code());
    this.status = status;
    this.envelope = envelope;
  }

  public int status() {
    return status;
  }

  public ErrorEnvelope envelope() {
    return envelope;
  }

  public String code() {
    return envelope.code();
  }
}
