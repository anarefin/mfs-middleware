package mfs.mw.adapter.bank.partner.kamelet;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import mfs.mw.adapter.bank.partner.AuthScheme;
import mfs.mw.adapter.bank.partner.CredentialRef;
import mfs.mw.adapter.bank.partner.OutboundCall;
import mfs.mw.adapter.bank.partner.auth.ApiKeyAuthScheme;
import mfs.mw.adapter.bank.partner.auth.HmacSha256AuthScheme;
import mfs.mw.adapter.bank.partner.route.PartnerExchange;
import mfs.mw.adapter.bank.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * The steps of the {@code mw-auth-*} Kamelets (bean {@code mwAuth}): the shipped auth schemes
 * ({@code partner/auth}) applied to the request {@code mw-http-call} prepared, so the wire format
 * is exactly the former framework's ({@code AuthSchemesTest}). The secret is the field {@code
 * secretField} of this partner's own OpenBao secret, read when its route loaded ({@link
 * PartnerRuntime#secret}); it is only ever written into the request headers, never logged, never
 * put in a URI or an audited header.
 */
@Component("mwAuth")
public class MwAuthStep {

  private final Clock clock;

  public MwAuthStep(Clock clock) {
    this.clock = clock;
  }

  /** {@code mw-auth-apikey}: the key in one header (default {@code X-Api-Key}). */
  public void apiKey(Exchange exchange, String secretField, String header) {
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    apply(exchange, new ApiKeyAuthScheme(blankTo(header, ApiKeyAuthScheme.DEFAULT_HEADER), ref -> runtime.secret(secretField)));
  }

  /** {@code mw-auth-hmac-sha256}: signature over method, path and query, timestamp and body hash. */
  public void hmac(Exchange exchange, String secretField, String keyId, String signatureHeader, String timestampHeader) {
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    apply(
        exchange,
        new HmacSha256AuthScheme(
            ref -> runtime.secret(secretField),
            clock,
            blankTo(signatureHeader, HmacSha256AuthScheme.SIGNATURE_HEADER),
            blankTo(timestampHeader, HmacSha256AuthScheme.TIMESTAMP_HEADER),
            keyId == null || keyId.isBlank() ? null : keyId,
            Duration.ofSeconds(300)));
  }

  private static void apply(Exchange exchange, AuthScheme scheme) {
    String body = exchange.getProperty(PartnerExchange.REQUEST_BODY, String.class);
    OutboundCall call =
        new OutboundCall(
            exchange.getProperty(PartnerExchange.HTTP_METHOD, String.class),
            URI.create("http://partner" + exchange.getProperty(PartnerExchange.HTTP_PATH_AND_QUERY, String.class)),
            body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    scheme.apply(call, new CredentialRef("route"));
    call.headers().forEach(exchange.getMessage()::setHeader);
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
