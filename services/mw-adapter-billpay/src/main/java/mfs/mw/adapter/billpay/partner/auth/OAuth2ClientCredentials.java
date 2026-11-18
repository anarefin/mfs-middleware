package mfs.mw.adapter.billpay.partner.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import mfs.mw.adapter.billpay.partner.route.PartnerExchange;
import mfs.mw.adapter.billpay.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth2 client credentials (RFC 6749 4.4) for {@code mw-auth-oauth2-cc} (ticket 87). The token
 * request is a POST of {@code grant_type=client_credentials} (with {@code scope} and {@code
 * audience} when set) to the record's {@code tokenUrl}, the client authenticated with HTTP Basic
 * (RFC 6749 2.3.1, each part form-encoded). It goes through Camel's {@code http} component like
 * every partner call, so the host guard holds the token URL to the partner's {@code allowedHosts}
 * and the DNS resolver to checked addresses, before any network I/O.
 *
 * <p>Camel's own {@code oauth2*} http options are not used: they take the client secret as an
 * endpoint option (in the endpoint URI) and do not drop a token the partner refused.
 *
 * <p>The token is cached per partner client (partner id, token URL, client id, scope, audience)
 * until {@code expires_in} minus the skew ({@code mw.partners.oauth2.expiry-skew}, default 30 s;
 * no {@code expires_in}: 60 s); concurrent calls wait for one token request. A partner 401 drops
 * the token it was sent with ({@link #token}'s {@code rejected} generation, empty for none), so one fresh token is
 * fetched. The token and the client secret are never logged, audited or put in a URI.
 */
@Component
public class OAuth2ClientCredentials {

  /** Lifetime of a token whose response has no {@code expires_in}. */
  static final Duration DEFAULT_LIFETIME = Duration.ofSeconds(60);

  private static final Logger log = LoggerFactory.getLogger(OAuth2ClientCredentials.class);

  /** One cache entry's key: the partner client a token is valid for. */
  public record Client(String partnerId, String tokenUrl, String clientId, String scope, String audience) {}

  /** A token and its generation (to drop exactly this one after a 401); never logged. */
  public record Token(String value, long generation) {
    @Override
    public String toString() {
      return "Token[generation=" + generation + "]";
    }
  }

  private record Cached(Token token, Instant expiresAt) {}

  private static final class Slot {
    final ReentrantLock lock = new ReentrantLock();
    volatile Cached cached;
  }

  private final ConcurrentHashMap<Client, Slot> slots = new ConcurrentHashMap<>();
  private final AtomicLong generations = new AtomicLong();
  private final ProducerTemplate producer;
  private final JsonMapper json;
  private final Clock clock;
  private final Duration skew;

  public OAuth2ClientCredentials(
      ProducerTemplate producer,
      JsonMapper json,
      Clock clock,
      @Value("${mw.partners.oauth2.expiry-skew:30s}") Duration skew) {
    this.producer = producer;
    this.json = json;
    this.clock = clock;
    this.skew = skew;
  }

  /**
   * A valid token for {@code client}: the cached one, else one fetched now.
   *
   * @param rejected generation of a token the partner just answered 401 to (dropped first); empty for none
   * @throws IllegalStateException when no token could be obtained (nothing was sent to the partner)
   */
  public Token token(PartnerRuntime runtime, Client client, String clientSecret, java.util.OptionalLong rejected) {
    Slot slot = slots.computeIfAbsent(client, c -> new Slot());
    slot.lock.lock();
    try {
      Cached cached = slot.cached;
      if (cached != null && rejected.isPresent() && cached.token().generation() == rejected.getAsLong()) {
        cached = null;
        slot.cached = null;
      }
      if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
        return cached.token();
      }
      Cached fresh = fetch(runtime, client, clientSecret);
      slot.cached = fresh;
      return fresh.token();
    } finally {
      slot.lock.unlock();
    }
  }

  private Cached fetch(PartnerRuntime runtime, Client client, String clientSecret) {
    String endpoint = client.tokenUrl()
        + "?throwExceptionOnFailure=false&followRedirects=false&headerFilterStrategy=#mwPartnerHeaderFilter"
        + "&connectTimeout=" + runtime.config().connectTimeoutMs()
        + "&responseTimeout=" + runtime.config().timeoutMs()
        + "&soTimeout=" + runtime.config().timeoutMs();
    StringBuilder form = new StringBuilder("grant_type=client_credentials");
    if (!client.scope().isEmpty()) {
      form.append("&scope=").append(encode(client.scope()));
    }
    if (!client.audience().isEmpty()) {
      form.append("&audience=").append(encode(client.audience()));
    }
    String basic = Base64.getEncoder().encodeToString(
        (encode(client.clientId()) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8));
    Exchange answer = producer.send(endpoint, exchange -> {
      exchange.setProperty(PartnerExchange.RUNTIME, runtime);
      var message = exchange.getMessage();
      message.setHeader(Exchange.HTTP_METHOD, "POST");
      message.setHeader(Exchange.CONTENT_TYPE, "application/x-www-form-urlencoded");
      message.setHeader("Accept", "application/json");
      message.setHeader("Authorization", "Basic " + basic);
      message.setBody(form.toString());
    });
    if (answer.getException() != null) {
      log.atWarn()
          .addKeyValue("event", "partner.oauth2.token_failed")
          .addKeyValue("partnerId", client.partnerId())
          .addKeyValue("errorClass", answer.getException().getClass().getName())
          .log("no OAuth2 token: the token request failed; not sent");
      throw new IllegalStateException("no OAuth2 token for partner " + client.partnerId(), answer.getException());
    }
    Integer status = answer.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
    JsonNode body = null;
    if (status != null && status == 200) {
      try {
        body = json.readTree(answer.getMessage().getBody(String.class));
      } catch (RuntimeException e) {
        body = null;
      }
    }
    String token = body == null || !body.path("access_token").isString() ? "" : body.path("access_token").asString();
    String type = body == null ? "" : body.path("token_type").asString("bearer");
    if (token.isBlank() || !"bearer".equalsIgnoreCase(type)) {
      log.atWarn()
          .addKeyValue("event", "partner.oauth2.token_failed")
          .addKeyValue("partnerId", client.partnerId())
          .addKeyValue("httpStatus", status)
          .log("no OAuth2 token: the token endpoint gave no bearer token; not sent");
      throw new IllegalStateException("no OAuth2 token for partner " + client.partnerId() + " (HTTP " + status + ")");
    }
    JsonNode expiresIn = body.path("expires_in");
    Duration lifetime = expiresIn.isNumber() || expiresIn.isString() && expiresIn.asString().matches("\\d{1,9}")
        ? Duration.ofSeconds(expiresIn.asLong())
        : DEFAULT_LIFETIME;
    Instant expiresAt = clock.instant().plus(lifetime.minus(skew).isNegative() ? Duration.ZERO : lifetime.minus(skew));
    log.atInfo()
        .addKeyValue("event", "partner.oauth2.token_fetched")
        .addKeyValue("partnerId", client.partnerId())
        .addKeyValue("expiresInS", lifetime.toSeconds())
        .log("OAuth2 token fetched");
    return new Cached(new Token(token, generations.incrementAndGet()), expiresAt);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
