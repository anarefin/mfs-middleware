package mfs.mw.adapter.billpay.partner.template;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import mfs.mw.adapter.billpay.partner.InboundCall;
import mfs.mw.adapter.billpay.partner.InboundResult;
import mfs.mw.adapter.billpay.partner.OutboundCall;
import mfs.mw.adapter.billpay.partner.PartnerAdapter;
import mfs.mw.adapter.billpay.partner.PartnerAuthenticationException;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerContext;
import mfs.mw.adapter.billpay.partner.PartnerNotSentException;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.billpay.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.billpay.partner.PartnerUnknownOutcomeException;
import mfs.mw.adapter.billpay.partner.PartnerUnsupportedException;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.mapping.JsonataMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter behind both REST/JSON route templates. One HTTP attempt per call (never retried
 * here: duplicates are absorbed by our stable reference, which partners deduplicate on).
 *
 * <p>Request: the operation's JSONata request mapping turns the canonical call into the body;
 * {@code {reference}} in the path becomes our reference. Static headers, {@code X-Correlation-Id}
 * and the auth scheme's headers are added.
 *
 * <p>Response, fail closed (spec 7.4; a rejection releases the hold): a 5xx is UNKNOWN before any
 * mapping; otherwise the response mapping reads {@code {status, body}} ({@code body} null when
 * not JSON) and says {@code {outcome, partnerRef, code, message, reason}}. The adapter then
 * believes SUCCESS (and PENDING, async template only) only on a 2xx with a partner reference,
 * FAILED only with a code and on one of the operation's {@code definiteStatuses}, NOT_FOUND only
 * for a status inquiry and on a definite status; a FAILED code listed as ambiguous is AMBIGUOUS.
 * Anything else is UNKNOWN ({@code UNEXPECTED_STATUS} for a believable answer on the wrong
 * status, {@code MALFORMED_REPLY} for the rest). Transport: a refused or timed-out connect is
 * NOT_SENT; a read timeout or a reset after sending is UNKNOWN.
 */
public final class RestJsonAdapter implements PartnerAdapter {

  public static final String CORRELATION_HEADER = "X-Correlation-Id";

  private static final Logger log = LoggerFactory.getLogger(RestJsonAdapter.class);

  private final String kind;
  private final boolean callbacks;
  private final JsonMapper json;

  public RestJsonAdapter(String kind, JsonMapper json) {
    this.kind = kind;
    this.callbacks = PartnerTemplates.ASYNC_CALLBACK.equals(kind);
    this.json = json;
  }

  @Override
  public String kind() {
    return kind;
  }

  @Override
  public PartnerResponse execute(PartnerCall call, PartnerContext ctx) {
    return send(call, ctx, false);
  }

  @Override
  public PartnerResponse inquire(PartnerCall call, PartnerContext ctx) {
    return send(call, ctx, true);
  }

  @Override
  public InboundResult onInbound(InboundCall call, PartnerContext ctx) {
    if (!callbacks) {
      throw new PartnerUnsupportedException(kind + " takes no callbacks");
    }
    if (!ctx.auth().verify(call, ctx.credentials())) {
      throw new PartnerAuthenticationException("callback of " + call.partnerId() + " is not authentic");
    }
    PartnerConfig.Operation op = ctx.config().operation(PartnerTemplates.CALLBACK_OPERATION);
    if (op == null) {
      throw new PartnerUnsupportedException(call.partnerId() + " has no CALLBACK mapping");
    }
    String raw = new String(call.body(), StandardCharsets.UTF_8);
    ctx.exchange().request(call.method(), call.pathAndQuery(), raw);
    Map<String, Object> input = new HashMap<>();
    input.put("headers", call.headers());
    input.put("body", parse(call.body()));
    Object out = JsonataMapping.compile(op.response()).evaluate(input);
    if (!(out instanceof Map<?, ?> m) || !(m.get("outcome") instanceof String outcome)
        || !java.util.Set.of("SUCCESS", "FAILED", "PENDING").contains(outcome)) {
      throw new JsonataMapping.MappingException("callback mapping gave no outcome", null);
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    if (m.get("attributes") instanceof Map<?, ?> attrs) {
      attrs.forEach((k, v) -> attributes.put(String.valueOf(k), text(v)));
    }
    return new InboundResult(
        text(m.get("partnerRef")), text(m.get("reference")), outcome, text(m.get("code")),
        text(m.get("message")), attributes);
  }

  private PartnerResponse send(PartnerCall call, PartnerContext ctx, boolean inquiry) {
    PartnerConfig config = ctx.config();
    PartnerConfig.Operation op = config.operation(call.operation());
    if (op == null) {
      throw new PartnerNotSentException(NotSentReason.MISCONFIGURED, null);
    }
    OutboundCall outbound = outbound(call, config, op);
    try {
      ctx.auth().apply(outbound, ctx.credentials());
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "partner.call.not_sent")
          .addKeyValue("errorClass", e.getClass().getName())
          .log("partner credentials could not be applied");
      throw new PartnerNotSentException(NotSentReason.MISCONFIGURED, e);
    }
    byte[] body = outbound.body();
    ctx.exchange()
        .request(outbound.method(), outbound.pathAndQuery(), new String(body, StandardCharsets.UTF_8));
    HttpRequest request;
    try {
      HttpRequest.Builder b =
          HttpRequest.newBuilder(outbound.uri())
              .timeout(config.readTimeout())
              .method(
                  outbound.method(),
                  body.length == 0
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofByteArray(body));
      outbound.headers().forEach(b::header);
      request = b.build();
    } catch (IllegalArgumentException e) {
      throw new PartnerNotSentException(NotSentReason.MISCONFIGURED, e);
    }
    HttpResponse<byte[]> response = exchange(ctx, request);
    byte[] responseBody = response.body() == null ? new byte[0] : response.body();
    ctx.exchange().response(response.statusCode(), new String(responseBody, StandardCharsets.UTF_8));
    return classify(response.statusCode(), responseBody, config, op, inquiry);
  }

  private OutboundCall outbound(PartnerCall call, PartnerConfig config, PartnerConfig.Operation op) {
    byte[] body = new byte[0];
    if (op.request() != null && !op.request().isBlank()) {
      Map<String, Object> input = new LinkedHashMap<>(call.input());
      input.put("reference", call.reference());
      input.put("operation", call.operation());
      input.put("txnId", call.txnId());
      try {
        Object mapped = JsonataMapping.compile(op.request()).evaluate(input);
        body = json.writeValueAsBytes(mapped);
      } catch (RuntimeException e) {
        log.atWarn()
            .addKeyValue("event", "partner.call.not_sent")
            .addKeyValue("errorClass", e.getClass().getName())
            .log("partner request mapping failed");
        throw new PartnerNotSentException(NotSentReason.MISCONFIGURED, e);
      }
    }
    String reference = call.reference() == null ? "" : call.reference();
    String path = op.path().replace("{reference}", URLEncoder.encode(reference, StandardCharsets.UTF_8));
    OutboundCall outbound = new OutboundCall(op.method(), target(config.baseUrl(), path), body);
    outbound.header("Accept", "application/json");
    if (body.length > 0) {
      outbound.header("Content-Type", "application/json");
    }
    config.headers().forEach(outbound::header);
    if (call.correlationId() != null) {
      outbound.header(CORRELATION_HEADER, call.correlationId());
    }
    return outbound;
  }

  /**
   * The request URI: base URL path + operation path, refused unsent unless it keeps the base URL's
   * scheme, host and port, has no user info and stays under the base path. Config validation
   * already refuses unsafe paths; this guard holds even for a config that bypassed it, so neither
   * the request nor its credentials can be sent anywhere but the allowlisted endpoint.
   */
  static URI target(URI baseUrl, String path) {
    String base = baseUrl.toString().replaceAll("/+$", "");
    URI target;
    try {
      target = new URI(base + path).normalize();
    } catch (java.net.URISyntaxException | RuntimeException e) {
      throw refusedTarget(e);
    }
    String basePath = baseUrl.getRawPath() == null ? "" : baseUrl.getRawPath().replaceAll("/+$", "");
    boolean same =
        baseUrl.getScheme().equalsIgnoreCase(String.valueOf(target.getScheme()))
            && target.getHost() != null
            && baseUrl.getHost().equalsIgnoreCase(target.getHost())
            && baseUrl.getPort() == target.getPort()
            && target.getRawUserInfo() == null
            && target.getRawPath() != null
            && target.getRawPath().startsWith(basePath + "/");
    if (!same) {
      throw refusedTarget(null);
    }
    return target;
  }

  private static PartnerNotSentException refusedTarget(Exception cause) {
    log.atWarn()
        .addKeyValue("event", "security.partner_target_refused")
        .log("partner request would leave the configured endpoint; not sent");
    return new PartnerNotSentException(NotSentReason.MISCONFIGURED, cause);
  }

  private static HttpResponse<byte[]> exchange(PartnerContext ctx, HttpRequest request) {
    try {
      return ctx.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PartnerUnknownOutcomeException(UnknownReason.TIMEOUT, e);
    } catch (IOException e) {
      throw transport(e);
    }
  }

  /** Connect failures mean nothing was sent; everything else may have been. */
  private static RuntimeException transport(IOException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
        log.atWarn()
            .addKeyValue("event", "partner.call.not_sent")
            .addKeyValue("errorClass", t.getClass().getName())
            .addKeyValue("detail", String.valueOf(t.getMessage()))
            .log("partner connect failed");
        return new PartnerNotSentException(NotSentReason.CONNECT_FAILED, e);
      }
      if (t instanceof HttpTimeoutException) {
        return new PartnerUnknownOutcomeException(UnknownReason.TIMEOUT, e);
      }
    }
    return new PartnerUnknownOutcomeException(UnknownReason.CONNECTION_RESET, e);
  }

  private PartnerResponse classify(
      int status, byte[] body, PartnerConfig config, PartnerConfig.Operation op, boolean inquiry) {
    if (status >= 500) {
      throw new PartnerUnknownOutcomeException(UnknownReason.HTTP_5XX, null);
    }
    Map<String, Object> input = new HashMap<>();
    input.put("status", status);
    input.put("body", parse(body));
    Object out;
    try {
      out = JsonataMapping.compile(op.response()).evaluate(input);
    } catch (RuntimeException e) {
      throw malformed(status);
    }
    if (!(out instanceof Map<?, ?> answer) || !(answer.get("outcome") instanceof String outcome)) {
      throw malformed(status);
    }
    boolean ok = status >= 200 && status < 300;
    boolean definite = op.definiteStatuses().contains(status);
    String partnerRef = text(answer.get("partnerRef"));
    String code = text(answer.get("code"));
    String message = text(answer.get("message"));
    return switch (outcome.toUpperCase(Locale.ROOT)) {
      case "SUCCESS" -> {
        require(ok, status);
        if (isBlank(partnerRef)) {
          throw malformed(status);
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (answer.get("attributes") instanceof Map<?, ?> attrs) {
          attrs.forEach(
              (k, v) -> {
                String t = text(v);
                if (t != null) {
                  attributes.put(String.valueOf(k), t);
                }
              });
        }
        yield new PartnerResponse.Success(partnerRef, code, attributes);
      }
      case "PENDING" -> {
        if (!callbacks) {
          throw malformed(status);
        }
        require(ok, status);
        yield new PartnerResponse.Pending(partnerRef, code);
      }
      case "FAILED" -> {
        if (isBlank(code)) {
          throw malformed(status);
        }
        require(definite, status);
        if (config.ambiguousCodes().contains(code)) {
          yield new PartnerResponse.Ambiguous(code, message);
        }
        PartnerConfig.ErrorMapping m = config.errorMapping(code);
        yield new PartnerResponse.Rejected(code, message, m.suffix(), m.retryable());
      }
      case "NOT_FOUND" -> {
        if (!inquiry) {
          throw malformed(status);
        }
        require(definite, status);
        yield new PartnerResponse.NotFound(code, message);
      }
      case "UNKNOWN" -> throw unknown(text(answer.get("reason")), status);
      default -> throw malformed(status);
    };
  }

  private Object parse(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      return json.readValue(body, Object.class);
    } catch (JacksonException e) {
      return null;
    }
  }

  private static void require(boolean believable, int status) {
    if (!believable) {
      throw unexpected(status);
    }
  }

  private static RuntimeException unknown(String reason, int status) {
    if ("UNEXPECTED_STATUS".equals(reason)) {
      return unexpected(status);
    }
    for (UnknownReason r : UnknownReason.values()) {
      if (r.name().equals(reason) && r != UnknownReason.MALFORMED_REPLY) {
        return new PartnerUnknownOutcomeException(r, null);
      }
    }
    return malformed(status);
  }

  private static PartnerUnknownOutcomeException unexpected(int status) {
    // 408, 409, 429, other 4xx, 1xx, 3xx: not a definite answer, the request may have been
    // applied, so never a rejection (a rejection releases the hold).
    log.atWarn()
        .addKeyValue("event", "partner.call.unexpected_status")
        .addKeyValue("httpStatus", status)
        .log("partner answered with an unexpected status");
    return new PartnerUnknownOutcomeException(UnknownReason.UNEXPECTED_STATUS, null);
  }

  private static PartnerUnknownOutcomeException malformed(int status) {
    log.atWarn()
        .addKeyValue("event", "partner.call.malformed")
        .addKeyValue("httpStatus", status)
        .log("partner reply not understood");
    return new PartnerUnknownOutcomeException(UnknownReason.MALFORMED_REPLY, null);
  }

  private static String text(Object v) {
    return v == null || v instanceof Map<?, ?> || v instanceof java.util.List<?> ? null : String.valueOf(v);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
