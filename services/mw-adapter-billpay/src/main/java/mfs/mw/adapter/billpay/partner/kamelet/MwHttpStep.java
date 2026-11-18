package mfs.mw.adapter.billpay.partner.kamelet;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import mfs.mw.adapter.billpay.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.billpay.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.billpay.partner.route.PartnerExchange;
import mfs.mw.adapter.billpay.partner.route.PartnerHostRefusedException;
import mfs.mw.adapter.billpay.partner.route.PartnerResult;
import mfs.mw.adapter.billpay.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java steps of the {@code mw-http-call} Kamelet (bean {@code mwHttp}). The Kamelet holds the
 * Camel side (resilience4j breaker with time limit and bulkhead, the {@code http} endpoint, the
 * INQUIRY/STATUS_QUERY loop); these steps prepare the request, read the response and turn a
 * fallback into an outcome, keeping the fail-closed rules of the former {@code RestJsonAdapter}:
 * nothing sent (refused connect, open breaker, full bulkhead, refused host) is NOT_SENT; anything
 * that may have reached the partner (time limit, 5xx, reset) is UNKNOWN. A PAY is sent once: only
 * an INQUIRY or STATUS_QUERY goes again, at most the record's {@code inquiryRetries}.
 */
@Component("mwHttp")
public class MwHttpStep {

  public static final String CORRELATION_HEADER = "X-Correlation-Id";

  /** {@code mw-http-call}'s {@code sslContextRef} (ticket 87); empty: no client certificate. */
  static final String TLS_REF = "mwHttpTlsRef";
  /** True when the call goes out with the named client context (the Kamelet's {@code toD} branch). */
  static final String TLS = "mwHttpTls";
  /** True once a partner 401 was answered by sending once more with a fresh credential. */
  static final String AUTH_RETRIED = "mwAuthRetried";

  private static final Logger log = LoggerFactory.getLogger(MwHttpStep.class);

  private final JsonMapper json;

  public MwHttpStep(JsonMapper json) {
    this.json = json;
  }

  /**
   * Sets method, path (our reference URL-encoded into {@code {reference}}) and headers from the
   * Kamelet's {@code method}, {@code url} and {@code path} (properties {@link
   * PartnerExchange#HTTP_METHOD}, {@link PartnerExchange#HTTP_BASE}, {@link
   * PartnerExchange#HTTP_PATH}); a header that would move the request elsewhere is removed.
   */
  public void prepare(Exchange exchange) {
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    var message = exchange.getMessage();
    for (String h : new String[] {
        Exchange.HTTP_URI, "CamelRestHttpUri", Exchange.HTTP_QUERY, Exchange.HTTP_RAW_QUERY, Exchange.HTTP_PATH}) {
      message.removeHeader(h);
    }
    String reference = message.getHeader(PartnerExchange.REFERENCE, "", String.class);
    String path =
        exchange.getProperty(PartnerExchange.HTTP_PATH, String.class)
            .replace("{reference}", URLEncoder.encode(reference, StandardCharsets.UTF_8));
    String base = exchange.getProperty(PartnerExchange.HTTP_BASE, String.class);
    String basePath = URI.create(base).getRawPath();
    basePath = basePath == null ? "" : basePath.replaceAll("/+$", "");
    exchange.setProperty(PartnerExchange.HTTP_PATH_AND_QUERY, basePath + path);
    message.setHeader(Exchange.HTTP_METHOD, exchange.getProperty(PartnerExchange.HTTP_METHOD, String.class));
    message.setHeader(Exchange.HTTP_PATH, path);
    message.setHeader("Accept", "application/json");
    String body = exchange.getProperty(PartnerExchange.REQUEST_BODY, String.class);
    if (body != null && !body.isEmpty()) {
      message.setHeader(Exchange.CONTENT_TYPE, "application/json");
    } else {
      message.removeHeader(Exchange.CONTENT_TYPE);
    }
    String tls = exchange.getProperty(TLS_REF, "", String.class);
    exchange.setProperty(TLS, tls != null && !tls.isEmpty());
    start(exchange, runtime);
  }

  /**
   * The start shared with {@code mw-soap-call}: the record's static headers, our correlation id,
   * no attempt yet, the first one to come, no credential refused.
   */
  static void start(Exchange exchange, PartnerRuntime runtime) {
    var message = exchange.getMessage();
    runtime.config().headers().forEach(message::setHeader);
    String correlationId = message.getHeader(PartnerExchange.CORRELATION_ID, String.class);
    if (correlationId != null) {
      message.setHeader(CORRELATION_HEADER, correlationId);
    }
    exchange.setProperty(PartnerExchange.ATTEMPTS, 0);
    exchange.setProperty(PartnerExchange.ATTEMPT_AGAIN, true);
    exchange.removeProperty(AUTH_RETRIED);
    exchange.removeProperty(MwAuthStep.REJECTED);
  }

  /** Before each attempt: the request body as mapped, nothing decided yet. */
  public void beforeAttempt(Exchange exchange) {
    nextAttempt(exchange);
    String body = exchange.getProperty(PartnerExchange.REQUEST_BODY, String.class);
    exchange.getMessage().setBody(body == null || body.isEmpty() ? null : body);
    exchange.setProperty(PartnerExchange.SENT, true);
  }

  /**
   * Each attempt, shared with {@code mw-soap-call}: counted, nothing decided or answered yet. The
   * caller marks it sent ({@link PartnerExchange#SENT}) once the request is ready, so a failure
   * before that stays NOT_SENT.
   */
  static void nextAttempt(Exchange exchange) {
    exchange.setProperty(PartnerExchange.ATTEMPT_AGAIN, false);
    exchange.setProperty(PartnerExchange.FINAL, false);
    exchange.setProperty(PartnerExchange.ATTEMPTS, exchange.getProperty(PartnerExchange.ATTEMPTS, 0, Integer.class) + 1);
    exchange.removeProperty(PartnerExchange.RESPONSE_STATUS);
    exchange.removeProperty(PartnerExchange.RESPONSE_BODY);
  }

  /**
   * Reads the partner's answer. A 5xx throws, so it counts against the breaker and ends in the
   * fallback as UNKNOWN {@code HTTP_5XX}; any other status is kept for {@code mw-classify}. A 401
   * to a refreshable credential ({@code mw-auth-oauth2-cc}, ticket 87) is answered once by sending
   * again with a fresh one (any operation: a 401 is a refusal, the request was not applied); the
   * extra attempt does not count against {@code inquiryRetries}.
   */
  public void response(Exchange exchange) {
    Integer status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
    String body = exchange.getMessage().getBody(String.class);
    exchange.setProperty(PartnerExchange.RESPONSE_STATUS, status);
    exchange.setProperty(PartnerExchange.RESPONSE_BODY, body == null ? "" : body);
    if (status == null || status >= 500) {
      throw new PartnerServerErrorException(status == null ? 0 : status);
    }
    if (status == 401
        && PartnerExchange.flag(exchange, MwAuthStep.REFRESHABLE)
        && !PartnerExchange.flag(exchange, AUTH_RETRIED)) {
      PartnerRuntime runtime = PartnerExchange.runtime(exchange);
      log.atInfo()
          .addKeyValue("event", "partner.auth.refresh")
          .addKeyValue("partnerId", runtime.id())
          .log("partner refused the credential (401); sending once more with a fresh one");
      exchange.setProperty(AUTH_RETRIED, true);
      exchange.setProperty(MwAuthStep.REJECTED, exchange.getProperty(MwAuthStep.TOKEN_GENERATION, Long.class));
      exchange.setProperty(PartnerExchange.ATTEMPTS, exchange.getProperty(PartnerExchange.ATTEMPTS, 1, Integer.class) - 1);
      exchange.setProperty(PartnerExchange.ATTEMPT_AGAIN, true);
    }
  }

  /**
   * The breaker's fallback: the outcome of a call that got no usable answer. An INQUIRY or
   * STATUS_QUERY is tried again ({@link PartnerExchange#ATTEMPT_AGAIN}) while the record's {@code
   * inquiryRetries} allow; a PAY never.
   */
  public void fallback(Exchange exchange) {
    Throwable failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
    boolean shortCircuited =
        Boolean.TRUE.equals(exchange.getProperty(ExchangePropertyKey.CIRCUIT_BREAKER_RESPONSE_SHORT_CIRCUITED, Boolean.class));
    // Camel marks every fallback response as short-circuited, so the exception decides; an open
    // breaker without one is the only short circuit left.
    PartnerResult result =
        failure == null && shortCircuited ? PartnerResult.notSent(NotSentReason.CIRCUIT_OPEN) : outcome(failure);
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    if ("NOT_SENT".equals(result.outcome())) {
      exchange.setProperty(PartnerExchange.SENT, false);
      log.atWarn()
          .addKeyValue("event", "partner.call.not_sent")
          .addKeyValue("partnerId", runtime.id())
          .addKeyValue("reason", result.reason())
          .addKeyValue("errorClass", failure == null ? null : failure.getClass().getName())
          .log("partner call not sent");
    }
    exchange.setProperty(PartnerExchange.FINAL, true);
    exchange.getMessage().setBody(json.writeValueAsString(result));
    int attempts = exchange.getProperty(PartnerExchange.ATTEMPTS, 1, Integer.class);
    String operation = PartnerExchange.operation(exchange);
    boolean again =
        !"PAY".equals(operation)
            && attempts <= runtime.config().inquiryRetries()
            && againAfter(result);
    exchange.setProperty(PartnerExchange.ATTEMPT_AGAIN, again);
  }

  private static boolean againAfter(PartnerResult result) {
    return switch (result.reason()) {
      case "TIMEOUT", "HTTP_5XX", "CONNECTION_RESET", "CONNECT_FAILED" -> true;
      default -> false;
    };
  }

  /** What a failure inside the breaker means; connect failures are the only "certainly not sent". */
  static PartnerResult outcome(Throwable failure) {
    if (failure == null) {
      return PartnerResult.unknown(UnknownReason.CONNECTION_RESET);
    }
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof CallNotPermittedException) {
        return PartnerResult.notSent(NotSentReason.CIRCUIT_OPEN);
      }
      if (t instanceof BulkheadFullException) {
        return PartnerResult.notSent(NotSentReason.BULKHEAD_FULL);
      }
      if (t instanceof PartnerHostRefusedException
          || t instanceof mfs.mw.adapter.billpay.partner.route.PartnerDnsResolver.AddressRefusedException) {
        return PartnerResult.notSent(NotSentReason.MISCONFIGURED);
      }
      if (t instanceof PartnerServerErrorException) {
        return PartnerResult.unknown(UnknownReason.HTTP_5XX);
      }
      // mw-soap-call: CXF's java.net.http conduit (ticket 85)
      if (t instanceof java.net.http.HttpConnectTimeoutException) {
        return PartnerResult.notSent(NotSentReason.CONNECT_FAILED);
      }
      if (t instanceof java.net.http.HttpTimeoutException) {
        return PartnerResult.unknown(UnknownReason.TIMEOUT);
      }
      if (t instanceof ConnectException || t instanceof UnknownHostException
          || t.getClass().getName().equals("org.apache.hc.client5.http.ConnectTimeoutException")) {
        return PartnerResult.notSent(NotSentReason.CONNECT_FAILED);
      }
      if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
        return PartnerResult.unknown(UnknownReason.TIMEOUT);
      }
    }
    return PartnerResult.unknown(UnknownReason.CONNECTION_RESET);
  }

  /** A 5xx answer: the request may have been applied. */
  public static final class PartnerServerErrorException extends RuntimeException {
    public PartnerServerErrorException(int status) {
      super("partner answered HTTP " + status, null, false, false);
    }
  }
}
