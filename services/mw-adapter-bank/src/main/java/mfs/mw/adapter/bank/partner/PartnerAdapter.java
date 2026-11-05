package mfs.mw.adapter.bank.partner;

/**
 * Adapter SPI (spec 12.1 layer 3). An adapter speaks one kind of partner protocol; the route
 * templates {@code rest-json-request-reply} and {@code rest-json-async-callback} are served by
 * {@link mfs.mw.adapter.bank.partner.template.RestJsonAdapter}, a partner of a new kind gets its
 * own implementation. Adapters never touch Kafka or the database: the framework wrapper ({@link
 * mfs.mw.adapter.bank.partner.template.PartnerCallWrapper}) adds bulkhead, circuit breaker, time
 * limiter, masked audit, correlation and metrics around every call.
 *
 * <p>Contract for implementations, so the wrapper can keep "unknown" apart from "failed": return
 * a {@link PartnerResponse} for every definite partner answer; throw {@link
 * PartnerNotSentException} when the request certainly never left, and {@link
 * PartnerUnknownOutcomeException} when it may have been applied. Both exceptions count towards the
 * partner's circuit; returned answers do not.
 */
public interface PartnerAdapter {

  /** Template or protocol name, e.g. {@code rest-json-request-reply}. */
  String kind();

  /** One outbound call (PAY and the like), exactly one attempt. */
  PartnerResponse execute(PartnerCall call, PartnerContext ctx);

  /** Status inquiry about an earlier call (spec 7.4); NOT_FOUND is only legal here. */
  default PartnerResponse inquire(PartnerCall call, PartnerContext ctx) {
    throw new PartnerUnsupportedException(kind() + " has no status inquiry");
  }

  /** A partner-initiated message (callback); verify it, then map it. */
  default InboundResult onInbound(InboundCall call, PartnerContext ctx) {
    throw new PartnerUnsupportedException(kind() + " takes no inbound messages");
  }
}
