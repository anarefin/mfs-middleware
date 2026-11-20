package mfs.mw.adapter.atm.controller;

/** Names of the inbound ATM SOAP API ({@code contracts/api/mw-adapter-atm/atm-soap.v1.xsd}). */
public final class AtmSoap {

  public static final String NAMESPACE = "urn:bits:mw:atm:v1";
  /** Servlet path; the WSDL is {@code PATH + "/atm.wsdl"}. */
  public static final String PATH = "/partner/ucbatm/ws/v1";
  public static final String WSSE_NS =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  public static final String WSU_NS =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
  /** Refused since ticket 120b1: the password would cross the wire. */
  public static final String PASSWORD_TEXT =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";
  public static final String PASSWORD_DIGEST =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest";
  public static final String BASE64_BINARY =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary";

  private AtmSoap() {}
}
