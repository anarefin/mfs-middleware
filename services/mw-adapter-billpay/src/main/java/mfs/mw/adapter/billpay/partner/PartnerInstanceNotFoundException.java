package mfs.mw.adapter.billpay.partner;

/** No partner config serves this partnerId (and there is no default). */
public class PartnerInstanceNotFoundException extends RuntimeException {

  public PartnerInstanceNotFoundException(String partnerId) {
    super("no partner config for " + partnerId);
  }
}
