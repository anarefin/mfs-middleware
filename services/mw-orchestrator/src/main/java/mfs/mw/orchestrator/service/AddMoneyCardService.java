package mfs.mw.orchestrator.service;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.mapper.PartnerReplyMapper;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import mfs.mw.orchestrator.repository.PartnerCallRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.AddMoneyCardFlow;
import mfs.mw.orchestrator.service.saga.Outcome;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The card legs of ADD_MONEY_CARD (ticket 118b, FRS UC-13, EXT-14, FL-07) that the generic partner path
 * cannot read: the CREATE_SESSION answer and the 3-D Secure webhook. Both run in the caller's
 * transaction with the row lock held. The AUTHORISE_CAPTURE answer (PAY) is a normal partner reply:
 * SUCCESS credits the wallet, FAILED fails, UNKNOWN goes to STATUS_QUERY.
 *
 * <ul>
 *   <li>Session SUCCESS while PARTNER_PENDING: the hosted page URL must be https on an allowed host
 *       ({@code mw.orchestrator.card.hosted-page-hosts}; else FAILED {@code MW-PTR-<PARTNER>-MALFORMED_REPLY},
 *       nothing moved); the session id becomes the partner reference, the status is published once more
 *       with {@code attributes.sessionId} and {@code hostedPageUrl}, and the deadline waits
 *       {@code mw.orchestrator.card.session-timeout} for the customer (then UNKNOWN -> STATUS_QUERY, which
 *       finds no payment and fails). Any other final session answer: FAILED, nothing moved.
 *   <li>Webhook while PARTNER_PENDING with the session open and no capture sent: AUTHENTICATED with a
 *       token sends AUTHORISE_CAPTURE (the token goes into that request only, never onto the record, the
 *       timeline, the audit or a log); FAILED (3-D Secure failure) ends FAILED with the acquirer's error.
 *       Anything else (late, repeated after the capture, in UNKNOWN): recorded as a late callback, nothing
 *       moves. A webhook is never read as the capture's outcome.
 * </ul>
 */
@Service
public class AddMoneyCardService {

  private static final Logger log = LoggerFactory.getLogger(AddMoneyCardService.class);

  public static final String SESSION_ID = "sessionId";
  public static final String HOSTED_PAGE_URL = "hostedPageUrl";
  public static final String TRANSIENT_TOKEN = "transientToken";

  private final SagaEngine engine;
  private final TransactionRepository transactions;
  private final PartnerCallRepository partnerCalls;
  private final PartnerReplyMapper mapper;
  private final Duration sessionTimeout;
  private final Set<String> hostedPageHosts;

  public AddMoneyCardService(
      SagaEngine engine,
      TransactionRepository transactions,
      PartnerCallRepository partnerCalls,
      PartnerReplyMapper mapper,
      @Value("${mw.orchestrator.card.session-timeout:PT15M}") Duration sessionTimeout,
      @Value("${mw.orchestrator.card.hosted-page-hosts:}") String hostedPageHosts) {
    this.engine = engine;
    this.transactions = transactions;
    this.partnerCalls = partnerCalls;
    this.mapper = mapper;
    this.sessionTimeout = sessionTimeout;
    this.hostedPageHosts = Arrays.stream(hostedPageHosts.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  /** True for a transaction of the card flow. */
  public static boolean isCard(TransactionRecord rec) {
    return AddMoneyCardFlow.TXN_TYPE.equals(rec.request().txnType());
  }

  /** The CREATE_SESSION (INQUIRY) answer. */
  public void sessionAnswer(TransactionRecord rec, PartnerReply reply) {
    Optional<Outcome> outcome = PartnerReplyMapper.outcome(reply);
    if (rec.status() != Status.PARTNER_PENDING || outcome.isEmpty() || outcome.get() == Outcome.PENDING
        || sessionOpen(rec)) {
      note(rec, "card.session.ignored", "card session answer does not fit the transaction's state; ignored");
      return;
    }
    if (outcome.get() != Outcome.SUCCESS) {
      ErrorInfo error = mapper.error(reply);
      engine.fire(rec, new SagaEvent.PartnerFailed(false), error != null ? error
          : new ErrorInfo(ErrorCodes.TMO_CORE_VALIDATE, "The card payment page could not be opened", null, true));
      return;
    }
    Map<String, String> attributes = reply.payload().attributes();
    String sessionId = attributes == null ? null : attributes.get(SESSION_ID);
    String url = attributes == null ? null : attributes.get(HOSTED_PAGE_URL);
    if (sessionId == null || sessionId.isBlank() || !allowedHostedPage(url)) {
      log.atWarn()
          .addKeyValue("event", "card.session.refused")
          .addKeyValue("txnId", rec.txnId().toString())
          .addKeyValue("partnerId", reply.partnerId())
          .log("card session answer without a session id or with a hosted page URL that is not allowed");
      engine.fire(rec, new SagaEvent.PartnerFailed(false), new ErrorInfo(
          "MW-PTR-" + String.valueOf(reply.partnerId()).toUpperCase(Locale.ROOT) + "-MALFORMED_REPLY",
          "The card payment page could not be opened", null, true));
      return;
    }
    TransactionRecord opened = rec.withPartnerRef(sessionId);
    transactions.update(opened);
    engine.fire(opened, new SagaEvent.CardSessionCreated(), null);
    engine.armPartnerPending(opened, sessionTimeout);
    engine.publishCardSession(opened, sessionId, url);
  }

  /** The 3-D Secure webhook (a CALLBACK of family card) of a transaction that passed the ownership checks. */
  public boolean callback(TransactionRecord rec, PartnerReply answer) {
    if (rec.status() != Status.PARTNER_PENDING || !sessionOpen(rec) || captureSent(rec)) {
      return false;
    }
    Optional<Outcome> outcome = PartnerReplyMapper.outcome(answer);
    Map<String, String> attributes = answer.payload().attributes();
    if (outcome.isPresent() && outcome.get() == Outcome.FAILED) {
      engine.fire(rec, new SagaEvent.PartnerFailed(false), mapper.error(answer));
      return true;
    }
    String sessionId = attributes == null ? null : attributes.get(SESSION_ID);
    String token = attributes == null ? null : attributes.get(TRANSIENT_TOKEN);
    if (outcome.isEmpty() || outcome.get() != Outcome.SUCCESS
        || token == null || token.isBlank() || !rec.partnerRef().equals(sessionId)) {
      note(rec, "card.callback.ignored", "3-D Secure webhook without a usable token for this session; ignored");
      return true;
    }
    engine.fire(rec, new SagaEvent.CardAuthenticated(), null);
    engine.sendCardCapture(rec, sessionId, token);
    return true;
  }

  /**
   * A capture SUCCESS (PAY or STATUS_QUERY) whose echoed {@code attributes.amount} (major units),
   * {@code currency} or {@code clientReference} is missing or differs from this transaction's (M9 review,
   * FL-07): a capture of another amount or of another reference must never credit the wallet.
   */
  public boolean captureMismatch(TransactionRecord rec, PartnerReply reply) {
    String op = reply.payload() == null ? null : reply.payload().operation();
    if (!(PartnerRequestMapper.PAY.equals(op) || PartnerRequestMapper.STATUS_QUERY.equals(op))
        || PartnerReplyMapper.outcome(reply).filter(o -> o == Outcome.SUCCESS).isEmpty()) {
      return false;
    }
    Map<String, String> a = reply.payload().attributes();
    var amount = rec.request().amount();
    if (a == null || a.get("amount") == null || amount == null) {
      return true;
    }
    try {
      boolean sameAmount = new java.math.BigDecimal(a.get("amount")).movePointRight(2)
          .compareTo(java.math.BigDecimal.valueOf(amount.valueMinor())) == 0;
      boolean sameCurrency = amount.currency() != null && amount.currency().equals(a.get("currency"));
      boolean sameReference =
          PartnerRequestMapper.partnerTxnRef(rec.txnId().toString()).equals(a.get("clientReference"));
      return !(sameAmount && sameCurrency && sameReference);
    } catch (NumberFormatException e) {
      return true;
    }
  }

  /**
   * A mismatched capture: ERROR {@code card.capture.mismatch}; while the capture is pending it becomes an
   * UNKNOWN outcome (status inquiries; never a credit, MANUAL_REVIEW when they run out), otherwise ignored.
   */
  public void refuseMismatchedCapture(TransactionRecord rec, PartnerReply reply) {
    log.atError()
        .addKeyValue("event", "card.capture.mismatch")
        .addKeyValue("txnId", rec.txnId().toString())
        .addKeyValue("partnerId", reply.partnerId())
        .addKeyValue("status", rec.status().name())
        .log("card capture success does not match the transaction's amount, currency or reference; not credited");
    if (rec.status() == Status.PARTNER_PENDING) {
      engine.fire(rec, new SagaEvent.PartnerUnknown(), null);
    }
  }

  /** https on an allowed host, nothing else (M9 review: never relay a javascript: or foreign URL). */
  boolean allowedHostedPage(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    try {
      URI uri = new URI(url);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && hostedPageHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
    } catch (java.net.URISyntaxException e) {
      return false;
    }
  }

  /** The session is open once its id is the partner reference. */
  private static boolean sessionOpen(TransactionRecord rec) {
    return rec.partnerRef() != null && !rec.partnerRef().isBlank();
  }

  private boolean captureSent(TransactionRecord rec) {
    return partnerCalls.of(rec.txnId()).stream().anyMatch(c -> PartnerRequestMapper.PAY.equals(c.operation()));
  }

  private static void note(TransactionRecord rec, String event, String message) {
    log.atInfo().addKeyValue("event", event).addKeyValue("txnId", rec.txnId().toString()).log(message);
  }
}
