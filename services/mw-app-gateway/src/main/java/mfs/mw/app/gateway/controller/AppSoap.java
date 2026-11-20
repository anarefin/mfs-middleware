package mfs.mw.app.gateway.controller;

/** The app SOAP facade (ticket 108): one namespace, the Idempotency-Key SOAP header. */
public final class AppSoap {

  public static final String NAMESPACE = "urn:mfs:mw:app:soap:v1";

  /** SOAP header element, the same key REST sends as the {@code Idempotency-Key} HTTP header. */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** Qualified name of {@link #IDEMPOTENCY_KEY} for {@code @SoapHeader}. */
  public static final String IDEMPOTENCY_HEADER = "{" + NAMESPACE + "}" + IDEMPOTENCY_KEY;

  private AppSoap() {}
}
