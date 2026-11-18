package mfs.mw.adapter.billpay.partner.kamelet;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import mfs.mw.adapter.billpay.partner.AuthScheme;
import mfs.mw.adapter.billpay.partner.CredentialRef;
import mfs.mw.adapter.billpay.partner.OutboundCall;
import mfs.mw.adapter.billpay.partner.auth.ApiKeyAuthScheme;
import mfs.mw.adapter.billpay.partner.auth.DetachedJws;
import mfs.mw.adapter.billpay.partner.auth.OAuth2ClientCredentials;
import mfs.mw.adapter.billpay.partner.auth.HmacSha256AuthScheme;
import mfs.mw.adapter.billpay.partner.route.PartnerExchange;
import mfs.mw.adapter.billpay.partner.route.PartnerRuntime;
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

  /** The header of {@code mw-auth-basic} and {@code mw-auth-oauth2-cc}. */
  public static final String AUTHORIZATION = "Authorization";

  /** Default header of {@code mw-auth-jws}. */
  public static final String JWS_HEADER = "X-JWS-Signature";

  /**
   * Set by an auth step whose credential can be refreshed ({@code mw-auth-oauth2-cc}): {@code
   * mw-http-call} then answers a partner 401 by sending once more with a fresh credential.
   */
  public static final String REFRESHABLE = "mwAuthRefreshable";
  /** Set by {@code mw-http-call} after a partner 401: the generation of the token it rejected. */
  public static final String REJECTED = "mwAuthRejected";
  /** The generation of the OAuth2 token on the request (never the token itself). */
  public static final String TOKEN_GENERATION = "mwAuthTokenGeneration";

  private final Clock clock;
  private final OAuth2ClientCredentials oauth2;

  public MwAuthStep(Clock clock, OAuth2ClientCredentials oauth2) {
    this.clock = clock;
    this.oauth2 = oauth2;
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

  /**
   * {@code mw-auth-basic} (ticket 87): {@code Authorization: Basic base64(user:password)}, RFC 7617
   * with UTF-8; both are fields of this partner's own secret.
   */
  public void basic(Exchange exchange, String userField, String secretField) {
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    String pair = runtime.secret(userField) + ":" + runtime.secret(secretField);
    exchange.getMessage().setHeader(
        AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * {@code mw-auth-jws} (ticket 87): the request body signed as a detached JWS ({@link
   * DetachedJws}) in {@code header} (default {@code X-JWS-Signature}); the PKCS#8 PEM key is a field
   * of this partner's own secret.
   */
  public void jws(Exchange exchange, String secretField, String alg, String header, String keyId) {
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    String body = exchange.getProperty(PartnerExchange.REQUEST_BODY, String.class);
    exchange.getMessage().setHeader(
        blankTo(header, JWS_HEADER),
        DetachedJws.sign(alg, keyId, runtime.secret(secretField), body == null ? null : body.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * {@code mw-auth-oauth2-cc} (ticket 87): {@code Authorization: Bearer <token>}, the client
   * credentials token of {@link OAuth2ClientCredentials} (cached per partner client; after a
   * partner 401 the rejected token is dropped first). Marks the request {@link #REFRESHABLE}.
   */
  public void oauth2(
      Exchange exchange, String secretField, String tokenUrl, String clientId, String scope, String audience) {
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    Long refused = exchange.getProperty(REJECTED, Long.class);
    java.util.OptionalLong rejected = refused == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(refused);
    OAuth2ClientCredentials.Token token = oauth2.token(
        runtime,
        new OAuth2ClientCredentials.Client(runtime.id(), tokenUrl, clientId, scope == null ? "" : scope, audience == null ? "" : audience),
        runtime.secret(secretField),
        rejected);
    exchange.removeProperty(REJECTED);
    exchange.setProperty(REFRESHABLE, true);
    exchange.setProperty(TOKEN_GENERATION, token.generation());
    exchange.getMessage().setHeader(AUTHORIZATION, "Bearer " + token.value());
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
