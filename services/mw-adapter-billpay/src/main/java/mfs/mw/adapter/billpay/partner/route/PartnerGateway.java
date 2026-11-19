package mfs.mw.adapter.billpay.partner.route;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import mfs.mw.adapter.billpay.partner.InboundCall;
import mfs.mw.adapter.billpay.partner.InboundResult;
import mfs.mw.adapter.billpay.partner.PartnerAuthenticationException;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerInstanceNotFoundException;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.billpay.partner.AuthScheme;
import mfs.mw.adapter.billpay.partner.CredentialRef;
import mfs.mw.adapter.billpay.partner.auth.AuthSchemes;
import mfs.mw.adapter.billpay.partner.auth.CallbackNonces;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import mfs.mw.adapter.billpay.partner.kamelet.MwAuditStep;
import mfs.mw.adapter.billpay.partner.mapping.CompiledMapping;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The services' one entry to the partners (ADR D28): Camel stays behind this class. A call
 * resolves its partnerId to the live version of a partner (its own record, else its {@code
 * partner-aliases} entry; else NOT_SENT {@code UNKNOWN_PARTNER}, never a silent default) and sends
 * it, synchronously, through that version's route {@code direct:partner-<id>-v<version>} with the
 * partner route contract ({@link PartnerExchange}) on the one shared {@link ProducerTemplate}. An
 * outcome is always returned, never thrown. Records the §21.2 metrics ({@code mw.partner.call},
 * {@code mw.partner.call.duration}, {@code mw.partner.circuit.state}).
 *
 * <p>Call it from a worker thread (the virtual-thread executor, an HTTP request thread), never
 * from a Kafka consumer thread. Callbacks ({@link #inbound}) are verified and mapped here in Java
 * with the record's {@code auth} and its {@code CALLBACK.response} mapping.
 */
@Component
public class PartnerGateway {

  private static final Logger log = LoggerFactory.getLogger(PartnerGateway.class);

  private final PartnerConfigStore store;
  private final ProducerTemplate producer;
  private final JsonMapper json;
  private final MwAuditStep results;
  private final MeterRegistry meters;
  private final CircuitBreakerRegistry breakers;
  private final Clock clock;
  private final CallbackNonces nonces;

  public PartnerGateway(
      PartnerConfigStore store,
      ProducerTemplate producer,
      JsonMapper json,
      MwAuditStep results,
      MeterRegistry meters,
      CircuitBreakerRegistry breakers,
      Clock clock,
      CallbackNonces nonces) {
    this.store = store;
    this.producer = producer;
    this.json = json;
    this.results = results;
    this.meters = meters;
    this.breakers = breakers;
    this.clock = clock;
    this.nonces = nonces;
  }

  /** Sends {@code call}; an outcome is always returned, never thrown. */
  public PartnerResponse call(PartnerCall call) {
    long start = System.nanoTime();
    Optional<PartnerRuntime> resolved = store.resolve(call.partnerId());
    PartnerResponse response;
    if (resolved.isEmpty()) {
      log.atWarn()
          .addKeyValue("event", "partner.unknown")
          .addKeyValue("partnerId", call.partnerId())
          .addKeyValue("txnId", call.txnId())
          .log("no partner and no alias for this partnerId; not sent");
      response = new PartnerResponse.NotSent(NotSentReason.UNKNOWN_PARTNER);
    } else if (!resolved.get().config().enabled()) {
      response = new PartnerResponse.NotSent(NotSentReason.DISABLED);
    } else {
      response = send(resolved.get(), call, start);
    }
    record(call, response, System.nanoTime() - start);
    return response;
  }

  /**
   * Sends a dry-run {@code call} through {@code candidate}, a version that is not live (ticket 83):
   * no partner resolution, no enabled check, no §21.2 metrics (they count real traffic). Audited
   * by {@code mw-audit} as every call.
   */
  public PartnerResponse dryRun(PartnerRuntime candidate, PartnerCall call) {
    return send(candidate, call, System.nanoTime());
  }

  private PartnerResponse send(PartnerRuntime runtime, PartnerCall original, long start) {
    registerCircuitGauge(runtime.id());
    PartnerCall call = withReversalReference(original);
    String body = json.writeValueAsString(call);
    Exchange done =
        producer.send(
            runtime.endpoint(),
            exchange -> {
              exchange.setProperty(PartnerExchange.RUNTIME, runtime);
              exchange.setProperty(PartnerExchange.OPERATION_PROPERTY, call.operation());
              exchange.setProperty(PartnerExchange.STARTED, start);
              var m = exchange.getMessage();
              m.setHeader(PartnerExchange.OPERATION, call.operation());
              m.setHeader(PartnerExchange.PARTNER_ID, call.partnerId());
              if (call.reference() != null) {
                m.setHeader(PartnerExchange.REFERENCE, call.reference());
              }
              if (call.correlationId() != null) {
                m.setHeader(PartnerExchange.CORRELATION_ID, call.correlationId());
              }
              if (call.txnId() != null) {
                m.setHeader(PartnerExchange.TXN_ID, call.txnId());
              }
              if (call.input().get("reversalReference") instanceof String reversal) {
                m.setHeader(PartnerExchange.REVERSAL_REFERENCE, reversal);
              }
              String code = partnerCode(call);
              if (code != null) {
                m.setHeader(PartnerExchange.PARTNER_CODE, code);
              }
              m.setBody(body);
            });
    if (done.getException() != null) {
      // the route was removed between resolve and send (a swap): nothing was sent
      log.atWarn()
          .addKeyValue("event", "partner.call.not_sent")
          .addKeyValue("partnerId", runtime.id())
          .addKeyValue("errorClass", done.getException().getClass().getName())
          .log("partner route not available");
      return PartnerExchange.flag(done, PartnerExchange.SENT)
          ? new PartnerResponse.Unknown(PartnerResponse.UnknownReason.MALFORMED_REPLY)
          : new PartnerResponse.NotSent(NotSentReason.DISABLED);
    }
    return results.response(done, done.getMessage().getBody(String.class));
  }

  /**
   * A REVERSE carries the idempotent reversal reference {@code reverse:<txnId>} (ticket 97) as
   * {@code input.reversalReference}, whatever the caller put there; the call's own {@code
   * reference} stays the original one. Any other call is returned as it is.
   */
  static PartnerCall withReversalReference(PartnerCall call) {
    if (!PartnerExchange.REVERSE.equals(call.operation()) || call.txnId() == null) {
      return call;
    }
    Map<String, Object> input = new LinkedHashMap<>(call.input());
    input.put("reversalReference", PartnerExchange.reversalReference(call.txnId()));
    return new PartnerCall(
        call.partnerId(), call.operation(), call.txnType(), call.txnId(), call.correlationId(), call.reference(), input);
  }

  /** {@code input.partner.billerCode}, else {@code productCode}; null when neither is a non-blank string. */
  static String partnerCode(PartnerCall call) {
    if (!(call.input().get("partner") instanceof Map<?, ?> partner)) {
      return null;
    }
    for (String key : new String[] {"billerCode", "productCode"}) {
      if (partner.get(key) instanceof String value && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /**
   * Verifies and maps a callback for {@code call.partnerId()} (its own record only, no alias):
   * the record's {@code auth} scheme with its secret, then its {@code CALLBACK.response} mapping
   * over {@code {headers, body}}.
   *
   * @throws PartnerInstanceNotFoundException no record of its own, or no callback mapping
   * @throws PartnerAuthenticationException the signature does not verify, or the nonce was used
   * @throws CompiledMapping.MappingException the body is not understood
   */
  public InboundResult inbound(InboundCall call) {
    PartnerRuntime runtime =
        store.instance(call.partnerId()).orElseThrow(() -> new PartnerInstanceNotFoundException(call.partnerId()));
    PartnerConfig config = runtime.config();
    CompiledMapping mapping = runtime.mappings().get("CALLBACK.response");
    if (mapping == null) {
      throw new PartnerInstanceNotFoundException(call.partnerId());
    }
    AuthScheme scheme = AuthSchemes.create(config.auth(), ref -> runtime.secret(config.auth().credentialsRef()), clock);
    if (!scheme.verify(call, new CredentialRef(config.auth().credentialsRef()))) {
      throw new PartnerAuthenticationException("callback of " + call.partnerId() + " is not authentic");
    }
    // ticket 99 (FRS PLT-008): a verified nonce is let through once; a replayed callback is refused
    // like a forged one (checked after the signature, so nobody without the secret can burn nonces)
    java.util.Optional<String> nonce = scheme.replayNonce(call);
    if (nonce.isPresent() && !nonces.firstUse(call.partnerId(), nonce.get(), scheme.replayWindow())) {
      throw new PartnerAuthenticationException("callback of " + call.partnerId() + " replays a nonce");
    }
    Map<String, Object> input = new HashMap<>();
    input.put("headers", call.headers());
    input.put("body", parse(call.body()));
    Object out = mapping.evaluate(input);
    if (!(out instanceof Map<?, ?> m) || !(m.get("outcome") instanceof String outcome)
        || !java.util.Set.of("SUCCESS", "FAILED", "PENDING").contains(outcome)) {
      throw new CompiledMapping.MappingException("callback mapping gave no outcome", null);
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    if (m.get("attributes") instanceof Map<?, ?> attrs) {
      attrs.forEach((k, v) -> attributes.put(String.valueOf(k), text(v)));
    }
    return new InboundResult(
        text(m.get("partnerRef")), text(m.get("reference")), outcome, text(m.get("code")), text(m.get("message")),
        attributes);
  }

  private Object parse(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      return json.readValue(new String(body, StandardCharsets.UTF_8), Object.class);
    } catch (JacksonException e) {
      return null;
    }
  }

  private static String text(Object v) {
    return v == null || v instanceof Map<?, ?> || v instanceof java.util.List<?> ? null : String.valueOf(v);
  }

  private void record(PartnerCall call, PartnerResponse response, long nanos) {
    String partner = call.partnerId() == null ? "unknown" : call.partnerId();
    String txnType = call.txnType() == null ? "unknown" : call.txnType();
    String operation = call.operation() == null ? "unknown" : call.operation();
    Counter.builder("mw.partner.call")
        .description("Partner calls by outcome (spec 21.2)")
        .tags("partner", partner, "txn_type", txnType, "operation", operation, "status", response.status())
        .register(meters)
        .increment();
    Timer.builder("mw.partner.call.duration")
        .description("Partner call duration (spec 21.2)")
        .tags("partner", partner, "txn_type", txnType, "operation", operation, "status", response.status())
        .publishPercentileHistogram()
        .register(meters)
        .record(Duration.ofNanos(nanos));
  }

  private void registerCircuitGauge(String partnerId) {
    CircuitBreaker breaker = breakers.circuitBreaker(partnerId);
    Gauge.builder("mw.partner.circuit.state", breaker, b -> circuitValue(b.getState()))
        .description("Circuit state: 0 closed, 1 open, 2 half-open, 3 other (spec 21.2)")
        .tags("partner", partnerId)
        .register(meters);
  }

  static double circuitValue(CircuitBreaker.State state) {
    return switch (state) {
      case CLOSED -> 0;
      case OPEN, FORCED_OPEN -> 1;
      case HALF_OPEN -> 2;
      default -> 3;
    };
  }
}
