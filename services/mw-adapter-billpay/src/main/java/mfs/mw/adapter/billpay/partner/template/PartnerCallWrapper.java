package mfs.mw.adapter.billpay.partner.template;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import mfs.mw.adapter.billpay.partner.ExchangeRecord;
import mfs.mw.adapter.billpay.partner.InboundCall;
import mfs.mw.adapter.billpay.partner.InboundResult;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerContext;
import mfs.mw.adapter.billpay.partner.PartnerNotSentException;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.billpay.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.billpay.partner.PartnerUnknownOutcomeException;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;

/**
 * The framework wrapper around every adapter call (spec 12.2): bulkhead, then circuit breaker
 * (Resilience4j instances named by the partner's {@code resilience}, default its id; spec 19.2),
 * then the partner's time limiter ({@code timeoutMs}), with the adapter running on the call
 * executor. Exactly one attempt: no retry here, ever (a payment is not idempotent from our side).
 * Afterwards it records the §21.2 metrics ({@code mw_partner_call_total}, {@code
 * mw_partner_call_duration_seconds}, {@code mw_partner_circuit_state}) and the masked audit.
 *
 * <p>Only exceptions count towards the circuit (transport failures, 5xx, timeouts, unreadable or
 * unexpected replies); definite answers such as business rejections do not. The config is read
 * from the {@link PartnerConfigStore} at call time, so a changed mapping or timeout applies to
 * the next call.
 */
public class PartnerCallWrapper {

  private final PartnerConfigStore store;
  private final CircuitBreakerRegistry breakers;
  private final BulkheadRegistry bulkheads;
  private final ExecutorService executor;
  private final MeterRegistry meters;
  private final PartnerAudit audit;

  public PartnerCallWrapper(
      PartnerConfigStore store,
      CircuitBreakerRegistry breakers,
      BulkheadRegistry bulkheads,
      ExecutorService executor,
      MeterRegistry meters,
      PartnerAudit audit) {
    this.store = store;
    this.breakers = breakers;
    this.bulkheads = bulkheads;
    this.executor = executor;
    this.meters = meters;
    this.audit = audit;
  }

  /** One outbound call through partner instance {@code instanceId}. Never throws for an outcome. */
  public PartnerResponse call(String instanceId, PartnerCall call) {
    PartnerInstance instance = store.instance(instanceId).orElse(null);
    if (instance == null || !instance.config().enabled()) {
      return new PartnerResponse.NotSent(NotSentReason.DISABLED);
    }
    if (instance.config().operation(call.operation()) == null) {
      return new PartnerResponse.NotSent(NotSentReason.MISCONFIGURED);
    }
    ExchangeRecord exchange = new ExchangeRecord();
    PartnerContext ctx =
        new PartnerContext(
            instance.id(), instance.config(), instance.auth(), instance.credentials(), instance.http(), exchange);
    boolean inquiry = PartnerTemplates.INQUIRY_OPERATION.equals(call.operation());
    CircuitBreaker breaker = breakers.circuitBreaker(instance.resilience());
    Bulkhead bulkhead = bulkheads.bulkhead(instance.resilience());
    registerCircuitGauge(call.partnerId(), breaker);
    long start = System.nanoTime();
    PartnerResponse response;
    try {
      response =
          bulkhead.executeSupplier(
              () ->
                  breaker.executeSupplier(
                      () ->
                          timeLimited(
                              instance,
                              () ->
                                  inquiry
                                      ? instance.adapter().inquire(call, ctx)
                                      : instance.adapter().execute(call, ctx))));
    } catch (BulkheadFullException e) {
      response = new PartnerResponse.NotSent(NotSentReason.BULKHEAD_FULL);
    } catch (CallNotPermittedException e) {
      response = new PartnerResponse.NotSent(NotSentReason.CIRCUIT_OPEN);
    } catch (PartnerNotSentException e) {
      response = new PartnerResponse.NotSent(e.reason());
    } catch (PartnerUnknownOutcomeException e) {
      response = new PartnerResponse.Unknown(e.reason());
    }
    long nanos = System.nanoTime() - start;
    record(call, response, nanos);
    audit.record(call, instance, exchange, response, Duration.ofNanos(nanos).toMillis());
    return response;
  }

  /** A callback for partner instance {@code instanceId}: verified and mapped by its adapter. */
  public InboundResult inbound(String instanceId, InboundCall call) {
    PartnerInstance instance =
        store.instance(instanceId).orElseThrow(() -> new IllegalStateException("no partner " + instanceId));
    PartnerContext ctx =
        new PartnerContext(
            instance.id(), instance.config(), instance.auth(), instance.credentials(), instance.http(),
            new ExchangeRecord());
    return instance.adapter().onInbound(call, ctx);
  }

  private PartnerResponse timeLimited(PartnerInstance instance, Supplier<PartnerResponse> call) {
    try {
      return instance.timeLimiter().executeFutureSupplier(() -> CompletableFuture.supplyAsync(call, executor));
    } catch (TimeoutException e) {
      throw new PartnerUnknownOutcomeException(UnknownReason.TIMEOUT, e);
    } catch (RuntimeException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PartnerUnknownOutcomeException(UnknownReason.TIMEOUT, e);
    } catch (Exception e) {
      throw new PartnerUnknownOutcomeException(UnknownReason.CONNECTION_RESET, e);
    }
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

  private void registerCircuitGauge(String partnerId, CircuitBreaker breaker) {
    Gauge.builder("mw.partner.circuit.state", breaker, b -> circuitValue(b.getState()))
        .description("Circuit state: 0 closed, 1 open, 2 half-open, 3 other (spec 21.2)")
        .tags("partner", partnerId == null ? "unknown" : partnerId)
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
