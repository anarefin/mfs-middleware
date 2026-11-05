package mfs.mw.app.gateway.exception;

import mfs.mw.app.gateway.dto.ErrorEnvelope;

/**
 * mw-adapter-billpay answered with its error envelope (unknown biller, mapped partner refusal,
 * partner timeout): a definite answer, never retried and not a circuit-breaker failure.
 */
public class BillPayRefusedException extends RuntimeException {

  private final int status;
  private final transient ErrorEnvelope envelope;

  public BillPayRefusedException(int status, ErrorEnvelope envelope) {
    super("billpay adapter answered " + status + " " + envelope.code());
    this.status = status;
    this.envelope = envelope;
  }

  public int status() {
    return status;
  }

  public ErrorEnvelope envelope() {
    return envelope;
  }
}
