package mfs.mw.adapter.billpay.partner.route;

/**
 * A partner route tried to reach a host its record does not allow (or to redirect the request with
 * {@code CamelHttpUri}); thrown by {@link PartnerHostGuard} before any network I/O.
 */
public class PartnerHostRefusedException extends RuntimeException {

  public PartnerHostRefusedException(String message) {
    super(message, null, false, false);
  }
}
