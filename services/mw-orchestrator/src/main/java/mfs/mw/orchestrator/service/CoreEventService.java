package mfs.mw.orchestrator.service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Flow;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The core's events on Kafka (spec 6.3 as amended by ADR D31). Since ticket 95a the saga takes the
 * core's answers from its REST calls ({@link CoreReplyService}); the result events the core still
 * publishes for the same operations are ignored here, so no answer is applied twice.
 *
 * <p>Ticket 95c (FRS 7.3) consumes the events that answer no call. {@code HoldExpired}: the core
 * already freed the reservation. A live saga that still holds, and whose partner has not succeeded
 * ({@code HOLDING}, {@code PARTNER_PENDING}, {@code UNKNOWN}), confirms that release. {@code
 * COMMITTING} means the partner already succeeded, so the saga stops in {@code MANUAL_REVIEW} and
 * sends nothing: the hold can no longer be committed. Wallet-state events ({@code
 * WalletStateChanged}, {@code WalletUnlocked}, {@code UnlockRejected}) name no saga this service
 * runs; a wallet projection is mw-auth's, and nothing here moves money because of them.
 */
@Service
public class CoreEventService {

  static final String CONSUMER = "orchestrator.hold-expiry";

  /** The core's own words for a hold it released on expiry (mw-core-mock {@code Reason.HOLD_EXPIRED}). */
  private static final ErrorInfo HOLD_EXPIRED =
      new ErrorInfo(
          "MW-CORE-HOLD_EXPIRED",
          "Hold expired and was released",
          "হোল্ডের মেয়াদ শেষ হয়ে টাকা মুক্ত হয়েছে",
          false);

  private static final Set<String> WALLET_STATE =
      Set.of("WalletStateChanged", "WalletUnlocked", "UnlockRejected");

  /** A hold is still this saga's to settle, and the partner has not succeeded. */
  private static final Set<Status> RELEASE_ON_EXPIRY =
      Set.of(Status.HOLDING, Status.PARTNER_PENDING, Status.UNKNOWN);

  private static final Logger log = LoggerFactory.getLogger(CoreEventService.class);

  private final Inbox inbox;
  private final TransactionRepository transactions;
  private final FlowRegistry flows;
  private final SagaEngine engine;

  public CoreEventService(
      Inbox inbox, TransactionRepository transactions, FlowRegistry flows, SagaEngine engine) {
    this.inbox = inbox;
    this.transactions = transactions;
    this.flows = flows;
    this.engine = engine;
  }

  /** Applies {@code event}. A repeated {@code HoldExpired} (same event id) changes nothing. */
  public void handle(CoreEvent event) {
    if ("HoldExpired".equals(event.eventType())) {
      inbox.processOnce(CONSUMER, event.eventId(), () -> expire(event));
      return;
    }
    if (WALLET_STATE.contains(event.eventType())) {
      log.atDebug()
          .addKeyValue("event", "core.wallet_state.consumed")
          .addKeyValue("txnId", event.txnId())
          .addKeyValue("eventType", event.eventType())
          .log("wallet-state event consumed; it names no saga");
      return;
    }
    log.atDebug()
        .addKeyValue("event", "core.event.ignored")
        .addKeyValue("txnId", event.txnId())
        .addKeyValue("eventType", event.eventType())
        .log("core event ignored: the saga takes the core's answers over REST");
  }

  private void expire(CoreEvent event) {
    UUID txnId = txnId(event.txnId());
    if (txnId == null) {
      drop(event, "txn.unknown", "hold expiry for an unknown transaction");
      return;
    }
    Optional<TransactionRecord> found = transactions.findForUpdate(txnId);
    if (found.isEmpty()) {
      drop(event, "txn.unknown", "hold expiry for an unknown transaction");
      return;
    }
    TransactionRecord rec = found.get();
    boolean holds = flows.find(rec.request().txnType()).map(Flow::needsHold).orElse(false);
    boolean partnerSucceeded = holds && rec.status() == Status.COMMITTING;
    boolean release = holds && RELEASE_ON_EXPIRY.contains(rec.status());
    if (!partnerSucceeded && !release) {
      drop(event, "saga.event.ignored", "hold expiry does not fit the transaction's state; ignored");
      return;
    }
    String holdId = holdId(event);
    if (holdId == null) {
      holdId = rec.holdId();
    }
    if (release && holdId == null) {
      drop(event, "saga.event.ignored", "hold expiry names no hold to release; ignored");
      return;
    }
    try {
      engine.fire(rec.withHold(holdId), new SagaEvent.HoldExpired(partnerSucceeded), HOLD_EXPIRED);
    } catch (InvalidTransitionException e) {
      drop(event, "saga.event.ignored", "hold expiry does not fit the transaction's state; ignored");
    }
  }

  private static UUID txnId(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The hold the core released, when the event names one. The payer's wallet id is not read. */
  private static String holdId(CoreEvent event) {
    JsonNode payload = event.payload();
    if (payload == null || !payload.hasNonNull("holdId")) {
      return null;
    }
    String id = payload.path("holdId").asString();
    return id.isBlank() ? null : id;
  }

  private static void drop(CoreEvent event, String name, String message) {
    log.atInfo()
        .addKeyValue("event", name)
        .addKeyValue("txnId", event.txnId())
        .addKeyValue("eventType", event.eventType())
        .log(message);
  }
}
