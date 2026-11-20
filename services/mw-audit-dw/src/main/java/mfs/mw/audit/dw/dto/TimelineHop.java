package mfs.mw.audit.dw.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * One hop of a transaction's audit timeline (ticket 130, FRS AUD-04, contract {@code
 * audit-query.v1} {@code TimelineHop}): the audit event, its hop (sent or inferred, {@code
 * util/Hops}), the hop's own latency ({@code durationMs}, when the producer measured it), the time
 * since the transaction's first hop ({@code sinceStartMs}) and since the previous hop ({@code
 * gapMs}), and its masked payloads. Values are as stored (already masked).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineHop(
    String auditId,
    String month,
    long seq,
    Instant at,
    String hop,
    String direction,
    String action,
    String service,
    AuditEvent.Actor actor,
    String resource,
    String outcome,
    Long durationMs,
    Long sinceStartMs,
    Long gapMs,
    String payloadRef,
    List<Payload> payloads) {

  /** A stored hop body: {@code IN} received by the middleware, {@code OUT} sent; sha256 in hex. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(
      String direction, String contentType, String body, String payloadRef, Integer size, String sha256) {}

  /** The hop of {@code e}, latencies relative to the timeline left unset. */
  public static TimelineHop of(AuditEntry e, List<Payload> payloads) {
    return new TimelineHop(
        e.auditId(), e.month(), e.seq(), e.at(), e.hop(), e.direction(), e.action(), e.service(), e.actor(),
        e.resource(), e.outcome(), e.durationMs(), null, null, e.payloadRef(), List.copyOf(payloads));
  }

  public TimelineHop withLatencies(long sinceStart, Long gap) {
    return new TimelineHop(
        auditId, month, seq, at, hop, direction, action, service, actor, resource, outcome, durationMs, sinceStart,
        gap, payloadRef, payloads);
  }

  /**
   * A transaction's timeline: its hops in the order they happened, {@code totalMs} from the first
   * to the last, and the anchor status of the audit trail (ticket 74).
   */
  public record Timeline(String txnId, List<TimelineHop> hops, long totalMs, AnchorStatus anchor) {}
}
