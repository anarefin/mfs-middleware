package mfs.mw.adapter.telco.service;

import java.util.Optional;
import mfs.mw.adapter.telco.client.OcsClient;
import mfs.mw.adapter.telco.dto.PartnerReply;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import mfs.mw.adapter.telco.exception.OcsNotSentException;
import mfs.mw.adapter.telco.exception.OcsUnknownOutcomeException;
import mfs.mw.adapter.telco.mapper.OcsRequestMapper;
import mfs.mw.adapter.telco.mapper.PartnerReplyMapper;
import mfs.mw.adapter.telco.messaging.EventMetadata;
import mfs.mw.adapter.telco.messaging.Inbox;
import mfs.mw.adapter.telco.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One canonical top-up request to one canonical reply (spec WP14): map, call the OCS once with
 * the stable partner reference, map the outcome, then write the reply to the outbox together with
 * the inbox record of the request (effectively-once, 6.4). The OCS call itself is outside any
 * database transaction.
 */
@Service
public class TopupService {

  static final String REPLY_TOPIC = "mw.partner.reply.v1";
  static final String CONSUMER = "mw-adapter-telco";

  private static final Logger log = LoggerFactory.getLogger(TopupService.class);

  private final OcsRequestMapper requests;
  private final PartnerReplyMapper replies;
  private final PartnerReferenceService references;
  private final OcsClient ocs;
  private final Inbox inbox;
  private final OutboxWriter outbox;

  public TopupService(
      OcsRequestMapper requests,
      PartnerReplyMapper replies,
      PartnerReferenceService references,
      OcsClient ocs,
      Inbox inbox,
      OutboxWriter outbox) {
    this.requests = requests;
    this.replies = replies;
    this.references = references;
    this.ocs = ocs;
    this.inbox = inbox;
    this.outbox = outbox;
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
    String partnerId = request.payload().partner().partnerId();
    String ref = references.referenceFor(request.txnId(), partnerId);
    try {
      return replies.toReply(request, ref, ocs.topup(requests.toOcs(request, ref)));
    } catch (OcsNotSentException e) {
      return replies.notSent(request, ref, e);
    } catch (OcsUnknownOutcomeException e) {
      return replies.unknown(request, ref, e);
    }
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
        .log("partner top-up handled");
  }
}
