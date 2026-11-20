package mfs.mw.adapter.interop.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import mfs.mw.adapter.interop.dto.PartnerReply;
import mfs.mw.adapter.interop.dto.PartnerRequest;
import mfs.mw.adapter.interop.mapper.PartnerCallMapper;
import mfs.mw.adapter.interop.mapper.PartnerReplyMapper;
import mfs.mw.adapter.interop.messaging.EventMetadata;
import mfs.mw.adapter.interop.messaging.Inbox;
import mfs.mw.adapter.interop.messaging.OutboxWriter;
import mfs.mw.adapter.interop.partner.route.PartnerGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One canonical interop request to one canonical reply (ticket 121: PAY = pacs.008, STATUS_QUERY = pacs.028, REVERSE = camt.056 return): check it, call
 * the NPSB switch once through the adapter framework with the stable partner reference (PAY: the transfer after the wallet debit
 * commit, refused unsent once its {@code deadlineAt} has passed;
 * REVERSE: the refund; STATUS_QUERY: the status inquiry of spec 7.4), map the
 * outcome, then write the reply to the outbox together with the inbox record of the request
 * (effectively-once, 6.4). The partner call itself is outside any database transaction; which
 * partner endpoint, wire format and error map apply is partner config ({@code partner} package).
 */
@Service
public class InteropPartnerService {

  static final String REPLY_TOPIC = "mw.partner.reply.v1";
  static final String CONSUMER = "mw-adapter-interop";
  /** Inbox consumer of the partner-call claim, committed before the call (ticket 82). */
  static final String CALL_CLAIM = "mw-adapter-interop.partner-call";

  private static final Logger log = LoggerFactory.getLogger(InteropPartnerService.class);

  private final PartnerCallMapper requests;
  private final PartnerReplyMapper replies;
  private final PartnerReferenceService references;
  private final PartnerGateway partners;
  private final Inbox inbox;
  private final OutboxWriter outbox;
  private final Clock clock;
  private final Counter duplicates;

  public InteropPartnerService(
      PartnerCallMapper requests,
      PartnerReplyMapper replies,
      PartnerReferenceService references,
      PartnerGateway partners,
      Inbox inbox,
      OutboxWriter outbox,
      Clock clock,
      MeterRegistry meters) {
    this.requests = requests;
    this.replies = replies;
    this.references = references;
    this.partners = partners;
    this.inbox = inbox;
    this.outbox = outbox;
    this.clock = clock;
    this.duplicates =
        Counter.builder("mw.partner.request.duplicate")
            .description("Partner requests delivered again after they were claimed; not sent again")
            .register(meters);
  }

  /**
   * Handles one request and writes exactly one reply for its requestId. The request is first
   * claimed in the inbox ({@value #CALL_CLAIM}, committed before the partner call): a request
   * delivered again by Kafka (a retry, a rebalance, a replay) after it was claimed calls nobody
   * and writes nothing, so a PAY reaches the partner once per requestId. If the instance dies
   * between claim and reply, the orchestrator's partner deadline moves the transaction to UNKNOWN
   * and the status inquiry resolves it (spec 7.4), exactly as for a crash during the call.
   */
  public void process(PartnerRequest request) {
    if (!inbox.processOnce(CALL_CLAIM, request.requestId(), () -> { })) {
      log.atInfo()
          .addKeyValue("event", "partner.call.duplicate")
          .addKeyValue("txnId", request.txnId())
          .addKeyValue("correlationId", request.correlationId())
          .log("partner request already handled; not sent again");
      duplicates.increment();
      return;
    }
    publish(request, replyFor(request));
  }

  private PartnerReply replyFor(PartnerRequest request) {
    Optional<String> problem = requests.problem(request);
    if (problem.isPresent()) {
      return replies.invalid(request, problem.get());
    }
    // a STATUS_QUERY and a REVERSE (ticket 97: a compensation, resent by the saga) are never refused for the deadline
    boolean inquiry = PartnerReplyMapper.isStatusQuery(request) || "REVERSE".equals(request.payload().operation());
    if (!inquiry && request.deadlineAt() != null && clock.instant().isAfter(request.deadlineAt())) {
      return replies.expired(request);
    }
    String partnerId = request.payload().partner().partnerId();
    String ref = references.referenceFor(request.txnId(), partnerId);
    return replies.toReply(request, ref, partners.call(requests.toCall(request, ref)));
  }

  private void publish(PartnerRequest request, PartnerReply reply) {
    EventMetadata meta =
        new EventMetadata(request.correlationId(), request.txnId(), request.requestId(), 1);
    boolean written =
        inbox.processOnce(
            CONSUMER, request.requestId(), () -> outbox.append(REPLY_TOPIC, request.txnId(), meta, reply));
    log.atInfo()
        .addKeyValue("event", "partner.call.completed")
        .addKeyValue("txnId", request.txnId())
        .addKeyValue("correlationId", request.correlationId())
        .addKeyValue("partnerId", reply.partnerId())
        .addKeyValue("outcome", reply.payload().outcome())
        .addKeyValue("errorCode", reply.payload().errorCode())
        .addKeyValue("replyWritten", written)
        .log("partner interop call handled");
  }
}
