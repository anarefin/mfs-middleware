package mfs.mw.adapter.telco.partner.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import mfs.mw.adapter.telco.partner.ExchangeRecord;
import mfs.mw.adapter.telco.partner.PartnerCall;
import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.util.MaskingPolicy;
import mfs.mw.adapter.telco.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit of the raw partner request and response (spec 12.2), masked before it leaves: JSON
 * fields listed in the partner's {@code sensitiveFields} are masked by their rule, then the whole
 * text is scrubbed (MSISDNs, NIDs, account numbers; 18.3). Headers are never audited (they carry
 * credentials). Bodies are cut at {@value #MAX_BODY} characters. The default sink writes one
 * {@code partner.call.audit} JSON log line per call; {@link #publishing} also hands every entry to
 * the service's audit publisher ({@code mw.audit.event.v1} through the outbox, spec 17).
 */
public final class PartnerAudit {

  static final int MAX_BODY = 4096;

  private static final Logger log = LoggerFactory.getLogger(PartnerAudit.class);

  /** One audited call; {@code request} and {@code response} are already masked. */
  public record Entry(
      String partnerId,
      String instance,
      String operation,
      String txnId,
      String correlationId,
      String method,
      String path,
      Integer httpStatus,
      String outcome,
      long durationMs,
      String request,
      String response) {}

  private final JsonMapper json;
  private final Consumer<Entry> sink;

  public PartnerAudit(JsonMapper json, Consumer<Entry> sink) {
    this.json = json;
    this.sink = sink;
  }

  /** Audit that logs {@code partner.call.audit} events. */
  public static PartnerAudit logging(JsonMapper json) {
    return new PartnerAudit(json, PartnerAudit::log);
  }

  /**
   * Audit that logs {@code partner.call.audit} and then hands the masked entry to {@code
   * publisher}. A failing publisher is logged ({@code partner.call.audit_failed}) and never fails
   * the partner call, whose outcome is already decided.
   */
  public static PartnerAudit publishing(JsonMapper json, Consumer<Entry> publisher) {
    return new PartnerAudit(
        json,
        e -> {
          log(e);
          try {
            publisher.accept(e);
          } catch (RuntimeException ex) {
            log.atError()
                .addKeyValue("event", "partner.call.audit_failed")
                .addKeyValue("partnerId", e.partnerId())
                .addKeyValue("txnId", e.txnId())
                .addKeyValue("operation", e.operation())
                .log("partner call audit could not be published: {}", ex.getClass().getSimpleName());
          }
        });
  }

  public void record(
      PartnerCall call,
      PartnerInstance instance,
      ExchangeRecord exchange,
      PartnerResponse response,
      long durationMs) {
    Map<String, SensitiveType> fields = instance.config().sensitiveFields();
    sink.accept(
        new Entry(
            call.partnerId(),
            instance.id(),
            call.operation(),
            call.txnId(),
            call.correlationId(),
            exchange.method(),
            exchange.path(),
            exchange.responseStatus(),
            response.status(),
            durationMs,
            mask(exchange.requestBody(), fields),
            mask(exchange.responseBody(), fields)));
  }

  /** Masks one raw body (JSON field rules, then free-text scrubbing). */
  String mask(String raw, Map<String, SensitiveType> fields) {
    if (raw == null || raw.isEmpty()) {
      return raw;
    }
    String text = raw;
    if (!fields.isEmpty()) {
      try {
        text = json.writeValueAsString(maskFields(json.readValue(raw, Object.class), fields));
      } catch (JacksonException e) {
        text = raw;
      }
    }
    String scrubbed = MaskingPolicy.scrub(text);
    return scrubbed.length() > MAX_BODY ? scrubbed.substring(0, MAX_BODY) + "..." : scrubbed;
  }

  private static Object maskFields(Object node, Map<String, SensitiveType> fields) {
    if (node instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach(
          (k, v) -> {
            SensitiveType type = fields.get(String.valueOf(k));
            out.put(
                String.valueOf(k),
                type != null && v != null && !(v instanceof Map<?, ?>) && !(v instanceof List<?>)
                    ? MaskingPolicy.mask(type, String.valueOf(v))
                    : maskFields(v, fields));
          });
      return out;
    }
    if (node instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      list.forEach(v -> out.add(maskFields(v, fields)));
      return out;
    }
    return node;
  }

  private static void log(Entry e) {
    log.atInfo()
        .addKeyValue("event", "partner.call.audit")
        .addKeyValue("partnerId", e.partnerId())
        .addKeyValue("partnerInstance", e.instance())
        .addKeyValue("operation", e.operation())
        .addKeyValue("txnId", e.txnId())
        .addKeyValue("correlationId", e.correlationId())
        .addKeyValue("httpMethod", e.method())
        .addKeyValue("path", e.path())
        .addKeyValue("httpStatus", e.httpStatus())
        .addKeyValue("outcome", e.outcome())
        .addKeyValue("durationMs", e.durationMs())
        .addKeyValue("request", e.request())
        .addKeyValue("response", e.response())
        .log("partner call audit");
  }
}
