package mfs.mw.adapter.merchant.partner.kamelet;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mfs.mw.adapter.merchant.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.merchant.partner.config.PartnerConfig;
import mfs.mw.adapter.merchant.partner.route.PartnerExchange;
import mfs.mw.adapter.merchant.partner.route.PartnerResult;
import mfs.mw.adapter.merchant.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The steps of the {@code mw-classify} Kamelet (bean {@code mwClassify}): the partner's answer to
 * a {@link PartnerResult}, fail closed (spec 7.4; a rejection releases the hold), with the rules of
 * the former {@code RestJsonAdapter.classify}, which no mapping can override.
 *
 * <p>The record's response mapping (Kamelet {@code mapping}) reads {@code {status, body}} ({@code
 * body} null when not JSON) and says {@code {outcome, partnerRef, code, message, reason,
 * attributes}}. SUCCESS (and PENDING, only with {@code pending=true}) is believed only on a 2xx with
 * a partner reference; FAILED only with a code and on one of {@code definiteStatuses}, then the
 * record's {@code ambiguousCodes} make it AMBIGUOUS and its {@code errorMap} gives the suffix;
 * NOT_FOUND only for a STATUS_QUERY and on a definite status. Anything else is UNKNOWN ({@code
 * UNEXPECTED_STATUS} for a believable answer on the wrong status, {@code MALFORMED_REPLY} for the
 * rest). An outcome already decided by {@code mw-http-call} (its fallback) is kept as it is.
 */
@Component("mwClassify")
public class MwClassifyStep {

  private static final Logger log = LoggerFactory.getLogger(MwClassifyStep.class);
  private static final int MAX_MESSAGE = 512;

  private final JsonMapper json;

  public MwClassifyStep(JsonMapper json) {
    this.json = json;
  }

  public void classify(Exchange exchange) {
    if (PartnerExchange.flag(exchange, PartnerExchange.FINAL)) {
      return;
    }
    PartnerResult result = classify(
        PartnerExchange.runtime(exchange),
        exchange.getProperty(PartnerExchange.MAPPING, String.class),
        statuses(exchange.getProperty(PartnerExchange.DEFINITE_STATUSES, "", String.class)),
        Boolean.parseBoolean(exchange.getProperty(PartnerExchange.ALLOW_PENDING, "false", String.class)),
        "STATUS_QUERY".equals(PartnerExchange.operation(exchange)),
        exchange.getProperty(PartnerExchange.RESPONSE_STATUS, Integer.class),
        exchange.getProperty(PartnerExchange.RESPONSE_BODY, String.class));
    exchange.getMessage().setBody(json.writeValueAsString(result));
  }

  /** {@code json-validator} refused the result: UNKNOWN {@code MALFORMED_REPLY}. */
  public void malformed(Exchange exchange) {
    log.atWarn()
        .addKeyValue("event", "partner.call.malformed")
        .addKeyValue("partnerId", PartnerExchange.runtime(exchange).id())
        .log("partner result failed its schema");
    exchange.getMessage().setBody(json.writeValueAsString(PartnerResult.unknown(UnknownReason.MALFORMED_REPLY)));
  }

  PartnerResult classify(
      PartnerRuntime runtime, String mapping, List<Integer> definite, boolean pending, boolean inquiry,
      Integer status, String rawBody) {
    if (status == null) {
      return malformed(0);
    }
    if (status >= 500) {
      return PartnerResult.unknown(UnknownReason.HTTP_5XX);
    }
    Map<String, Object> input = new HashMap<>();
    input.put("status", status);
    input.put("body", parse(rawBody));
    Object out;
    try {
      out = runtime.mapping(mapping).evaluate(input);
    } catch (RuntimeException e) {
      return malformed(status);
    }
    if (!(out instanceof Map<?, ?> answer) || !(answer.get("outcome") instanceof String outcome)) {
      return malformed(status);
    }
    boolean ok = status >= 200 && status < 300;
    boolean isDefinite = definite.contains(status);
    PartnerConfig config = runtime.config();
    String partnerRef = text(answer.get("partnerRef"));
    String code = text(answer.get("code"));
    String message = cut(text(answer.get("message")));
    return switch (outcome.toUpperCase(Locale.ROOT)) {
      case "SUCCESS" -> {
        if (!ok) {
          yield unexpected(status);
        }
        if (isBlank(partnerRef)) {
          yield malformed(status);
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (answer.get("attributes") instanceof Map<?, ?> attrs) {
          attrs.forEach((k, v) -> {
            String t = text(v);
            if (t != null) {
              attributes.put(String.valueOf(k), t);
            }
          });
        }
        yield new PartnerResult("SUCCESS", partnerRef, code, null, null, null, null, attributes.isEmpty() ? null : attributes);
      }
      case "PENDING" -> {
        if (!pending) {
          yield malformed(status);
        }
        yield ok
            ? new PartnerResult("PENDING", partnerRef, code, null, null, null, null, null)
            : unexpected(status);
      }
      case "FAILED" -> {
        if (isBlank(code)) {
          yield malformed(status);
        }
        if (!isDefinite) {
          yield unexpected(status);
        }
        if (config.ambiguousCodes().contains(code)) {
          yield new PartnerResult("AMBIGUOUS", null, code, message, null, null, null, null);
        }
        PartnerConfig.ErrorMapping m = config.errorMapping(code);
        yield new PartnerResult("FAILED", null, code, message, null, m.suffix(), m.retryable(), null);
      }
      case "NOT_FOUND" -> {
        if (!inquiry) {
          yield malformed(status);
        }
        yield isDefinite ? new PartnerResult("NOT_FOUND", null, code, message, null, null, null, null) : unexpected(status);
      }
      case "UNKNOWN" -> unknown(text(answer.get("reason")), status);
      default -> malformed(status);
    };
  }

  private Object parse(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    try {
      return json.readValue(body, Object.class);
    } catch (JacksonException e) {
      return null;
    }
  }

  static List<Integer> statuses(String csv) {
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> s.matches("\\d{3}"))
        .map(Integer::valueOf)
        .toList();
  }

  private static PartnerResult unknown(String reason, int status) {
    if ("UNEXPECTED_STATUS".equals(reason)) {
      return unexpected(status);
    }
    for (UnknownReason r : UnknownReason.values()) {
      if (r.name().equals(reason) && r != UnknownReason.MALFORMED_REPLY) {
        return PartnerResult.unknown(r);
      }
    }
    return malformed(status);
  }

  private static PartnerResult unexpected(int status) {
    // 408, 409, 429, other 4xx, 1xx, 3xx: not a definite answer, the request may have been
    // applied, so never a rejection (a rejection releases the hold).
    log.atWarn()
        .addKeyValue("event", "partner.call.unexpected_status")
        .addKeyValue("httpStatus", status)
        .log("partner answered with an unexpected status");
    return PartnerResult.unknown(UnknownReason.UNEXPECTED_STATUS);
  }

  private static PartnerResult malformed(int status) {
    log.atWarn()
        .addKeyValue("event", "partner.call.malformed")
        .addKeyValue("httpStatus", status)
        .log("partner reply not understood");
    return PartnerResult.unknown(UnknownReason.MALFORMED_REPLY);
  }

  private static String text(Object v) {
    return v == null || v instanceof Map<?, ?> || v instanceof List<?> ? null : String.valueOf(v);
  }

  private static String cut(String s) {
    return s == null || s.length() <= MAX_MESSAGE ? s : s.substring(0, MAX_MESSAGE);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
