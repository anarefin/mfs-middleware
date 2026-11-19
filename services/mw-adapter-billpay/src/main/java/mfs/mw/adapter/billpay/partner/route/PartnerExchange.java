package mfs.mw.adapter.billpay.partner.route;

import org.apache.camel.Exchange;

/**
 * The partner route contract on a Camel exchange (ADR D28, ticket 82).
 *
 * <p>In: the body is the {@code PartnerCall} JSON; the headers {@link #OPERATION} ({@code PAY},
 * {@code INQUIRY}, {@code STATUS_QUERY}, {@code REVERSE}, {@code HEALTH_CHECK}; ticket 97), {@link #REFERENCE}, {@link #CORRELATION_ID}, {@link
 * #PARTNER_ID} (as the caller named it), {@link #TXN_ID}, {@link #PARTNER_CODE}. The {@code mw*} headers never leave
 * the service ({@code mwPartnerHeaderFilter} drops them from the HTTP request and keeps a partner's
 * response from setting them). The Kamelets read what they rely on from exchange properties set by
 * {@link PartnerGateway}: {@link #RUNTIME} (the loaded record version the call runs on, so a call in
 * flight during a swap stays on its version) and {@link #OPERATION_PROPERTY}.
 *
 * <p>Out: the body is the {@link PartnerResult} JSON {@code {outcome, partnerRef, code, message,
 * reason, ...}} that {@code mw-classify} produced and checked with {@code json-validator}.
 */
public final class PartnerExchange {

  public static final String OPERATION = "mwOperation";

  /**
   * Ticket 97: undoes an applied call. {@link #REFERENCE} stays our reference of the original call
   * (the {@code {reference}} of a path); the mapping input and the header {@link
   * #REVERSAL_REFERENCE} carry the idempotent reversal reference {@code reverse:<txnId>}. Sent once,
   * as a PAY: never again by the engine (only the saga resends, ticket 96).
   */
  public static final String REVERSE = "REVERSE";
  /** Ticket 97: the optional health probe of a record with {@code healthCheck: true}. */
  public static final String HEALTH_CHECK = "HEALTH_CHECK";
  /** The reversal reference {@code reverse:<txnId>} of a REVERSE (absent otherwise). */
  public static final String REVERSAL_REFERENCE = "mwReversalReference";
  public static final String REFERENCE = "mwReference";
  public static final String CORRELATION_ID = "mwCorrelationId";
  public static final String PARTNER_ID = "mwPartnerId";
  public static final String TXN_ID = "mwTxnId";
  /**
   * The partner-side code of the call: {@code input.partner.billerCode}, else {@code productCode}
   * (absent: neither), so a route can branch per biller or product.
   */
  public static final String PARTNER_CODE = "mwPartnerCode";

  /** The {@link PartnerRuntime} of the call. */
  public static final String RUNTIME = "mwPartner";
  /** The operation of the call, as the gateway set it (a route cannot change it). */
  public static final String OPERATION_PROPERTY = "mwOperation";
  /** The request body {@code mw-map} produced (absent: no body). */
  public static final String REQUEST_BODY = "mwRequestBody";
  /** True once {@code mw-http-call} has handed the request to the HTTP client. */
  public static final String SENT = "mwSent";
  /** True once the outcome is decided without a reply to classify (fallback, refusal). */
  public static final String FINAL = "mwFinal";
  /** The {@code mw-http-call} request: method, path and query as sent, base URL. */
  public static final String HTTP_METHOD = "mwHttpMethod";
  public static final String HTTP_PATH = "mwHttpPath";
  public static final String HTTP_BASE = "mwHttpBase";
  public static final String HTTP_PATH_AND_QUERY = "mwHttpPathAndQuery";
  /** The partner's raw response (status, body), for the masked audit. */
  public static final String RESPONSE_STATUS = "mwResponseStatus";
  public static final String RESPONSE_BODY = "mwResponseBody";
  /** {@code mw-http-call}: set to true by its fallback when an INQUIRY / STATUS_QUERY goes again. */
  public static final String ATTEMPT_AGAIN = "mwAttemptAgain";
  public static final String ATTEMPTS = "mwAttempts";
  /** Kamelet parameters handed to the beans through properties. */
  public static final String MAPPING = "mwMapping";
  public static final String DEFINITE_STATUSES = "mwDefiniteStatuses";
  public static final String ALLOW_PENDING = "mwAllowPending";
  /** When the call started ({@link System#nanoTime()}), for the audit's duration. */
  public static final String STARTED = "mwStarted";

  private PartnerExchange() {}

  /** The runtime of the call; fails when a Kamelet runs outside a partner call. */
  public static PartnerRuntime runtime(Exchange exchange) {
    PartnerRuntime runtime = exchange.getProperty(RUNTIME, PartnerRuntime.class);
    if (runtime == null) {
      throw new IllegalStateException("not a partner call");
    }
    return runtime;
  }

  public static String operation(Exchange exchange) {
    return exchange.getProperty(OPERATION_PROPERTY, String.class);
  }

  /** The reversal reference of a REVERSE of transaction {@code txnId}: {@code reverse:<txnId>}. */
  public static String reversalReference(String txnId) {
    return "reverse:" + txnId;
  }

  /** True for an operation that may have moved money and is never sent again by the engine (PAY, REVERSE). */
  public static boolean sentOnce(String operation) {
    return !"INQUIRY".equals(operation) && !"STATUS_QUERY".equals(operation) && !HEALTH_CHECK.equals(operation);
  }

  public static boolean flag(Exchange exchange, String property) {
    return Boolean.TRUE.equals(exchange.getProperty(property, Boolean.class));
  }
}
