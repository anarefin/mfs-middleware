package mfs.mw.adapter.telco.partner;

/** The adapter has no such capability (inquiry, inbound) for this partner. */
public class PartnerUnsupportedException extends RuntimeException {

  public PartnerUnsupportedException(String message) {
    super(message);
  }
}
