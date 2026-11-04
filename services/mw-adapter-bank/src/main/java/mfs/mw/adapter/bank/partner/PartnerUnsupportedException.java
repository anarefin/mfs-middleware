package mfs.mw.adapter.bank.partner;

/** The adapter has no such capability (inquiry, inbound) for this partner. */
public class PartnerUnsupportedException extends RuntimeException {

  public PartnerUnsupportedException(String message) {
    super(message);
  }
}
