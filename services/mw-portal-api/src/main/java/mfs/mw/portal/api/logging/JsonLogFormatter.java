package mfs.mw.portal.api.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.net.InetAddress;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import mfs.mw.portal.api.util.MaskingPolicy;
import org.slf4j.event.KeyValuePair;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/**
 * JSON log line with the spec 21.1 fields, one event per line, UTC ISO-8601 {@code ts}. Masking
 * runs here, before output (18.3): every log argument is rendered with {@link
 * MaskingPolicy#describe(Object)} (so {@code @Sensitive} record components are masked) and the
 * whole message, key-value pairs and stack trace are scrubbed for MSISDNs, NIDs and account
 * numbers ({@link MaskingPolicy#scrub(String)}). Numeric field values are scrubbed as text too
 * and written as the masked string when masking changes them. The 21.1 id fields are traced by
 * exact match, so system ids among them are written unmasked, digit runs and all ({@link
 * #isSystemId}): a {@code txnId} or {@code correlationId} that is a valid lower-case RFC 9562
 * UUID and a {@code traceId} / {@code spanId} of the lower-case W3C hex shape. Shape is enough
 * because {@code CorrelationIdFilter} is the gate: a caller correlation id or traceparent with a
 * run of 6+ digits, or one {@link MaskingPolicy#scrub(String)} would change (such as a UUID that
 * spreads an MSISDN over its groups), never gets past it. Any other value, {@code partnerId}
 * always, is scrubbed like free text.
 *
 * <p>Context fields come from the MDC or from SLF4J key-value pairs ({@code
 * log.atInfo().addKeyValue("event", "txn.state.changed")}); key-value pairs win. Wired by {@code
 * logging.structured.format.console} in {@code application.yml}.
 */
public class JsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

  /** Context fields of 21.1, in output order, taken from MDC or key-value pairs. */
  static final List<String> CONTEXT_FIELDS =
      List.of(
          "correlationId", "txnId", "traceId", "spanId", "partnerId", "channel", "event");

  private static final List<String> TRAILING_FIELDS = List.of("durationMs", "errorCode");

  /**
   * A lower-case RFC 9562 UUID: version nibble 1-8, variant 8-b. What {@code UUID.randomUUID()}
   * and the txn id generators produce.
   */
  private static final Pattern RFC_UUID =
      Pattern.compile(
          "[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

  private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

  private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

  private final JsonWriter<Map<String, Object>> writer = JsonWriter.<Map<String, Object>>standard()
      .withNewLineAtEnd();
  private final String service;
  private final String version;
  private final String env;
  private final String host;

  public JsonLogFormatter(Environment environment) {
    this.service = environment.getProperty("spring.application.name", "unknown");
    this.version = environment.getProperty("mw.service.version", "unknown");
    this.env = environment.getProperty("mw.service.env", "unknown");
    this.host = hostName();
  }

  @Override
  public String format(ILoggingEvent event) {
    Map<String, Object> context = new LinkedHashMap<>(event.getMDCPropertyMap());
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    if (pairs != null) {
      for (KeyValuePair kv : pairs) {
        context.put(kv.key, kv.value);
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ts", event.getInstant().truncatedTo(ChronoUnit.MILLIS).toString());
    out.put("level", event.getLevel().toString());
    out.put("service", service);
    out.put("version", version);
    out.put("host", host);
    out.put("env", env);
    for (String field : CONTEXT_FIELDS) {
      putIfPresent(out, field, context.remove(field));
    }
    out.put("message", message(event));
    for (String field : TRAILING_FIELDS) {
      putIfPresent(out, field, context.remove(field));
    }
    out.put("logger", event.getLoggerName());
    out.put("thread", event.getThreadName());
    context.forEach((k, v) -> putIfPresent(out, k, v));
    IThrowableProxy error = event.getThrowableProxy();
    if (error != null) {
      out.put("stackTrace", MaskingPolicy.scrub(ThrowableProxyUtil.asString(error)));
    }
    return writer.writeToString(out);
  }

  private static String message(ILoggingEvent event) {
    Object[] args = event.getArgumentArray();
    String text;
    if (args == null || args.length == 0) {
      text = event.getMessage();
    } else {
      Object[] masked = new Object[args.length];
      for (int i = 0; i < args.length; i++) {
        masked[i] = args[i] instanceof Throwable ? null : MaskingPolicy.describe(args[i]);
      }
      text = MessageFormatter.basicArrayFormat(event.getMessage(), masked);
    }
    return MaskingPolicy.scrub(text);
  }

  private static void putIfPresent(Map<String, Object> out, String key, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof Boolean) {
      out.put(key, value);
    } else if (value instanceof Number) {
      String text = value.toString();
      String scrubbed = MaskingPolicy.scrub(text);
      out.put(key, text.equals(scrubbed) ? value : scrubbed);
    } else if (value instanceof String id && isSystemId(key, id)) {
      out.put(key, id);
    } else {
      out.put(key, MaskingPolicy.describe(value));
    }
  }

  /**
   * Whether {@code id} in field {@code key} is a system id, written unmasked (21.1), on its shape
   * alone. {@code CorrelationIdFilter} is the gate: it replaces a caller correlation id and drops a
   * caller traceparent that holds a 6+ digit run or that the scrub would change, so every
   * correlation, trace and span id that reaches here is generated or scrub-safe, and a txnId is a
   * parsed UUID. Shape alone keeps a system id with a 10+ digit run (about one UUID in 40) whole,
   * so it stays traceable by exact match.
   */
  private static boolean isSystemId(String key, String id) {
    return switch (key) {
      case "txnId", "correlationId" -> RFC_UUID.matcher(id).matches();
      case "traceId" -> TRACE_ID.matcher(id).matches();
      case "spanId" -> SPAN_ID.matcher(id).matches();
      default -> false;
    };
  }

  private static String hostName() {
    String h = System.getenv("HOSTNAME");
    if (h != null && !h.isBlank()) {
      return h;
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
