package mfs.mw.adapter.billpay.partner;

/** The request may have reached the partner and its outcome is not known (spec 7.4). */
public class PartnerUnknownOutcomeException extends RuntimeException {

  private final PartnerResponse.UnknownReason reason;

  public PartnerUnknownOutcomeException(PartnerResponse.UnknownReason reason, Throwable cause) {
    super(reason.name(), cause);
    this.reason = reason;
  }

  public PartnerResponse.UnknownReason reason() {
    return reason;
  }
}
