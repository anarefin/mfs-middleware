package mfs.mw.adapter.bank.partner;

/** An inbound partner message failed its auth scheme's verification (answer 401, publish nothing). */
public class PartnerAuthenticationException extends RuntimeException {

  public PartnerAuthenticationException(String message) {
    super(message);
  }
}
