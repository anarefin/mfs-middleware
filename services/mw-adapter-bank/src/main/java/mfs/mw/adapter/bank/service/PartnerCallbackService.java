package mfs.mw.adapter.bank.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.adapter.bank.dto.CallbackAck;
import mfs.mw.adapter.bank.dto.PartnerInbound;
import mfs.mw.adapter.bank.exception.ErrorCodes;
import mfs.mw.adapter.bank.exception.MwException;
import mfs.mw.adapter.bank.mapper.PartnerInboundMapper;
import mfs.mw.adapter.bank.messaging.EventMetadata;
import mfs.mw.adapter.bank.messaging.EventPublisher;
import mfs.mw.adapter.bank.messaging.Inbox;
import mfs.mw.adapter.bank.partner.InboundCall;
import mfs.mw.adapter.bank.partner.InboundResult;
import mfs.mw.adapter.bank.partner.PartnerAuthenticationException;
import mfs.mw.adapter.bank.partner.PartnerInstanceNotFoundException;
import mfs.mw.adapter.bank.partner.PartnerUnsupportedException;
import mfs.mw.adapter.bank.partner.config.PartnerConfigStore;
import mfs.mw.adapter.bank.partner.mapping.JsonataMapping;
import mfs.mw.adapter.bank.partner.template.PartnerGateway;
import mfs.mw.adapter.bank.partner.template.PartnerInstance;
import mfs.mw.adapter.bank.repository.PartnerReferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Partner callbacks (ticket 22, spec 11.2 inbound path): verify, deduplicate, write to Kafka,
 * then acknowledge.
 *
 * <ol>
 *   <li>The partner named by the URL must have config of its own (no fallback to the default
 *       partner: a callback is only taken for the partner that signs it).
 *   <li>The framework verifies the signature with that partner's auth scheme and maps the body
 *       ({@link PartnerGateway#inbound}): not authentic 401 {@code MW-AUTH-001}; unreadable 400.
 *   <li>The reference must be one this adapter issued for that partner ({@code
 *       partner_reference}); it gives the txnId. Unknown 404.
 *   <li>Dedup and publish in one step: the inbox row ({@code inboundId} = partner + reference +
 *       outcome) and the synchronous Kafka send ({@code acks=all}) share one database
 *       transaction, so the 200 goes out only once the event is on Kafka; a Kafka failure rolls
 *       the inbox row back and answers 503 (the partner sends it again). A duplicate is
 *       acknowledged without a second record. Were the commit to fail after the send, the
 *       partner's resend publishes the same {@code inboundId}, which the orchestrator drops.
 * </ol>
 *
 * Runs on the HTTP request's (virtual) thread; never touches the orchestrator's state.
 */
@Service
public class PartnerCallbackService {

  public static final String TOPIC = "mw.partner.inbound.v1";
  static final String CONSUMER = "mw-adapter-bank.callbacks";

  private static final Logger log = LoggerFactory.getLogger(PartnerCallbackService.class);

  private final PartnerConfigStore store;
  private final PartnerGateway partners;
  private final PartnerReferenceRepository references;
  private final PartnerInboundMapper mapper;
  private final Inbox inbox;
  private final EventPublisher publisher;
  private final Clock clock;

  public PartnerCallbackService(
      PartnerConfigStore store,
      PartnerGateway partners,
      PartnerReferenceRepository references,
      PartnerInboundMapper mapper,
      Inbox inbox,
      EventPublisher publisher,
      Clock clock) {
    this.store = store;
    this.partners = partners;
    this.references = references;
    this.mapper = mapper;
    this.inbox = inbox;
    this.publisher = publisher;
    this.clock = clock;
  }

  public CallbackAck receive(InboundCall call) {
    PartnerInstance instance =
        store.instance(call.partnerId()).orElseThrow(() -> notFound(call.partnerId(), "partner.callback.unknown_partner"));
    InboundResult result = verifyAndMap(call);
    String reference = result.reference();
    Optional<PartnerReferenceRepository.Issued> issued =
        reference == null ? Optional.empty() : references.findByReference(reference);
    if (issued.isEmpty() || !call.partnerId().equals(issued.get().partnerId())) {
      throw notFound(call.partnerId(), "partner.callback.unknown_reference");
    }
    String txnId = issued.get().txnId();
    PartnerInbound event =
        mapper.toInbound(result, instance.config(), call.partnerId(), txnId, correlationId(), clock.instant());
    boolean published;
    try {
      published =
          inbox.processOnce(
              CONSUMER,
              event.inboundId(),
              () ->
                  publisher.publish(
                      TOPIC, txnId, new EventMetadata(event.correlationId(), txnId, event.inboundId(), 1), event));
    } catch (RuntimeException e) {
      log.atError()
          .addKeyValue("event", "partner.callback.not_written")
          .addKeyValue("txnId", txnId)
          .addKeyValue("partnerId", call.partnerId())
          .addKeyValue("errorClass", e.getClass().getName())
          .log("partner callback could not be written to Kafka; not acknowledged");
      throw new MwException(ErrorCodes.SYS_EVENT_NOT_WRITTEN, "The callback could not be recorded; send it again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
    log.atInfo()
        .addKeyValue("event", "partner.callback.received")
        .addKeyValue("txnId", txnId)
        .addKeyValue("partnerId", call.partnerId())
        .addKeyValue("outcome", event.payload().outcome())
        .addKeyValue("errorCode", event.payload().errorCode())
        .addKeyValue("duplicate", !published)
        .log(published ? "partner callback published" : "duplicate partner callback acknowledged");
    return CallbackAck.accepted(event.inboundId(), !published);
  }

  private InboundResult verifyAndMap(InboundCall call) {
    try {
      return partners.inbound(call);
    } catch (PartnerAuthenticationException e) {
      log.atWarn()
          .addKeyValue("event", "security.partner_callback_rejected")
          .addKeyValue("partnerId", call.partnerId())
          .log("partner callback signature did not verify; rejected, nothing published");
      throw new MwException(ErrorCodes.AUTH_SIGNATURE, "The callback signature is not valid").withStatus(HttpStatus.UNAUTHORIZED);
    } catch (PartnerInstanceNotFoundException | PartnerUnsupportedException e) {
      throw notFound(call.partnerId(), "partner.callback.unsupported");
    } catch (JsonataMapping.MappingException e) {
      log.atWarn()
          .addKeyValue("event", "partner.callback.unreadable")
          .addKeyValue("partnerId", call.partnerId())
          .log("partner callback verified but not understood; refused");
      throw new MwException(ErrorCodes.VAL_MALFORMED, "The callback body is not understood").withStatus(HttpStatus.BAD_REQUEST);
    }
  }

  private static MwException notFound(String partnerId, String event) {
    log.atWarn().addKeyValue("event", event).addKeyValue("partnerId", partnerId).log("partner callback refused: not found");
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No such callback target").withStatus(HttpStatus.NOT_FOUND);
  }

  private static String correlationId() {
    String c = MDC.get("correlationId");
    return c == null || c.isBlank() ? UUID.randomUUID().toString() : c;
  }
}
