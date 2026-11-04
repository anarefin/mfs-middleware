package mfs.mw.adapter.bank.partner;

/** The request certainly never reached the partner, so its outcome is a definite "not done". */
public class PartnerNotSentException extends RuntimeException {

  private final PartnerResponse.NotSentReason reason;

  public PartnerNotSentException(PartnerResponse.NotSentReason reason, Throwable cause) {
    super(reason.name(), cause);
    this.reason = reason;
  }

  public PartnerResponse.NotSentReason reason() {
    return reason;
  }
}
