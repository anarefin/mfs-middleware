package mfs.mw.adapter.bank.service;

import java.time.Clock;
import java.util.Optional;
import mfs.mw.adapter.bank.dto.PartnerReply;
import mfs.mw.adapter.bank.dto.PartnerRequest;
import mfs.mw.adapter.bank.mapper.PartnerCallMapper;
import mfs.mw.adapter.bank.mapper.PartnerReplyMapper;
import mfs.mw.adapter.bank.messaging.EventMetadata;
import mfs.mw.adapter.bank.messaging.Inbox;
import mfs.mw.adapter.bank.messaging.OutboxWriter;
import mfs.mw.adapter.bank.partner.template.PartnerGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One canonical bank request to one canonical reply (spec WP16, C2W debit): check it, call the bank once
 * through the adapter framework with the stable partner reference (PAY: the debit of the customer's bank account, refused unsent
 * once its {@code deadlineAt} has passed; STATUS_QUERY: the status inquiry of spec 7.4), map the
 * outcome, then write the reply to the outbox together with the inbox record of the request
 * (effectively-once, 6.4). The partner call itself is outside any database transaction; which
 * partner endpoint, wire format and error map apply is partner config ({@code partner} package).
 */
@Service
public class BankDebitService {

  static final String REPLY_TOPIC = "mw.partner.reply.v1";
  static final String CONSUMER = "mw-adapter-bank";

  private static final Logger log = LoggerFactory.getLogger(BankDebitService.class);

  private final PartnerCallMapper requests;
  private final PartnerReplyMapper replies;
  private final PartnerReferenceService references;
  private final PartnerGateway partners;
  private final Inbox inbox;
  private final OutboxWriter outbox;
  private final Clock clock;

  public BankDebitService(
      PartnerCallMapper requests,
      PartnerReplyMapper replies,
      PartnerReferenceService references,
      PartnerGateway partners,
      Inbox inbox,
      OutboxWriter outbox,
      Clock clock) {
    this.requests = requests;
    this.replies = replies;
    this.references = references;
    this.partners = partners;
    this.inbox = inbox;
    this.outbox = outbox;
    this.clock = clock;
  }

  /** Handles one request and writes exactly one reply for its requestId. */
  public void process(PartnerRequest request) {
    publish(request, replyFor(request));
  }

  private PartnerReply replyFor(PartnerRequest request) {
    Optional<String> problem = requests.problem(request);
    if (problem.isPresent()) {
      return replies.invalid(request, problem.get());
    }
    boolean inquiry = PartnerReplyMapper.isStatusQuery(request);
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
        .log("partner bank debit handled");
  }
}
