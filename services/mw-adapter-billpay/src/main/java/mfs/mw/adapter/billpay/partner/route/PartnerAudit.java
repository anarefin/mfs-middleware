package mfs.mw.adapter.billpay.partner.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import mfs.mw.adapter.billpay.util.MaskingPolicy;
import mfs.mw.adapter.billpay.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Audit of the raw partner request and response (spec 12.2), masked before it leaves: JSON
 * fields listed in the partner's {@code sensitiveFields} are masked by their rule, then the whole
 * text is scrubbed (MSISDNs, NIDs, account numbers; 18.3). Headers are never audited (they carry
 * credentials). The entry's bodies are cut at {@value #MAX_BODY} characters. The default sink writes
 * one {@code partner.call.audit} JSON log line per call; {@link #publishing} also hands every entry
 * to the service's audit publisher ({@code mw.audit.event.v1} through the outbox, spec 17).
 *
 * <p>Ticket 97 (raw payload store): with a {@link PayloadStore} the full masked request and response
 * (not cut) go to the adapter's own {@code partner_payload} table first, with the call's latency,
 * under one {@code payloadRef} ({@code partner-payload/<uuid>}) that the entry, and so the audit
 * event, carries. A failing store is logged ({@code partner.call.payload_failed}) and the entry goes
 * out without a {@code payloadRef}; neither ever fails the call.
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
      String response,
      String payloadRef) {

    /** An entry whose full bodies were not stored. */
    public Entry(
        String partnerId, String instance, String operation, String txnId, String correlationId, String method,
        String path, Integer httpStatus, String outcome, long durationMs, String request, String response) {
      this(partnerId, instance, operation, txnId, correlationId, method, path, httpStatus, outcome, durationMs, request,
          response, null);
    }
  }

  /** One stored body: direction {@code REQUEST} or {@code RESPONSE}, masked, not cut. */
  public record Payload(
      String payloadRef, String txnId, String partnerId, String operation, String direction, String body, long latencyMs) {}

  /** Where the full masked bodies go (the adapter's {@code partner_payload} table). */
  @FunctionalInterface
  public interface PayloadStore {
    void store(List<Payload> payloads);
  }

  private final JsonMapper json;
  private final Consumer<Entry> sink;
  private final PayloadStore payloads;

  public PartnerAudit(JsonMapper json, Consumer<Entry> sink) {
    this(json, sink, null);
  }

  public PartnerAudit(JsonMapper json, Consumer<Entry> sink, PayloadStore payloads) {
    this.json = json;
    this.sink = sink;
    this.payloads = payloads;
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
    return publishing(json, publisher, null);
  }

  /** {@link #publishing(JsonMapper, Consumer)} that stores the full masked bodies in {@code payloads} first. */
  public static PartnerAudit publishing(JsonMapper json, Consumer<Entry> publisher, PayloadStore payloads) {
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
        },
        payloads);
  }

  /**
   * Audits one partner call; {@code request} and {@code response} are the raw bodies, masked here
   * with the record's {@code sensitiveFields} before they leave.
   */
  public void record(
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
      String response,
      Map<String, SensitiveType> fields) {
    String maskedRequest = maskFull(request, fields);
    String maskedResponse = maskFull(response, fields);
    String payloadRef = store(partnerId, operation, txnId, durationMs, maskedRequest, maskedResponse);
    sink.accept(
        new Entry(
            partnerId, instance, operation, txnId, correlationId, method, path, httpStatus, outcome,
            durationMs, cut(maskedRequest), cut(maskedResponse), payloadRef));
  }

  private String store(
      String partnerId, String operation, String txnId, long durationMs, String request, String response) {
    if (payloads == null || (request == null && response == null)) {
      return null;
    }
    String ref = "partner-payload/" + java.util.UUID.randomUUID();
    List<Payload> rows = new ArrayList<>();
    if (request != null) {
      rows.add(new Payload(ref, txnId, partnerId, operation, "REQUEST", request, durationMs));
    }
    if (response != null) {
      rows.add(new Payload(ref, txnId, partnerId, operation, "RESPONSE", response, durationMs));
    }
    try {
      payloads.store(rows);
      return ref;
    } catch (RuntimeException e) {
      log.atError()
          .addKeyValue("event", "partner.call.payload_failed")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("txnId", txnId)
          .addKeyValue("operation", operation)
          .log("partner call payload could not be stored: {}", e.getClass().getSimpleName());
      return null;
    }
  }

  /** Masks one raw body and cuts it at {@value #MAX_BODY} characters (the audit excerpt). */
  String mask(String raw, Map<String, SensitiveType> fields) {
    return cut(maskFull(raw, fields));
  }

  private static String cut(String masked) {
    return masked == null || masked.length() <= MAX_BODY ? masked : masked.substring(0, MAX_BODY) + "...";
  }

  /** Masks one raw body (JSON field rules, then free-text scrubbing), whole. */
  String maskFull(String raw, Map<String, SensitiveType> fields) {
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
    return MaskingPolicy.scrub(text);
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
        .addKeyValue("payloadRef", e.payloadRef())
        .log("partner call audit");
  }
}
