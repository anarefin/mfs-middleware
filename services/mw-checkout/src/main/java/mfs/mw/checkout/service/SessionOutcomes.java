package mfs.mw.checkout.service;

import java.time.Clock;
import java.util.UUID;
import mfs.mw.checkout.dto.ErrorInfo;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.dto.StatusMessage;
import mfs.mw.checkout.dto.TerminalStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a payment's outcome lands on its session (spec 9.3): PAYING -> PAID for a
 * COMPLETED transaction, PAYING -> FAILED for FAILED/REJECTED. Reached by the paying request when
 * the answer comes within the budget, and by the {@code mw.txn.status.v1} projection for any
 * answer (late or not); whichever is first wins, the other is a no-op. Then every {@link
 * SessionOutcomeListener} runs in the same transaction (the IPN seam, ticket 32).
 */
@Service
public class SessionOutcomes {

  private static final Logger log = LoggerFactory.getLogger(SessionOutcomes.class);

  private final SessionRepository sessions;
  private final ObjectProvider<SessionOutcomeListener> listeners;
  private final Clock clock;

  public SessionOutcomes(
      SessionRepository sessions, ObjectProvider<SessionOutcomeListener> listeners, Clock clock) {
    this.sessions = sessions;
    this.listeners = listeners;
    this.clock = clock;
  }

  /** Applies a status record of a checkout transaction; anything not final is ignored. */
  @Transactional
  public void apply(StatusMessage status) {
    if (status.duplicate() || !status.terminal() || status.txnId() == null) {
      return;
    }
    UUID txnId;
    try {
      txnId = UUID.fromString(status.txnId());
    } catch (IllegalArgumentException e) {
      return;
    }
    TerminalStatus terminal = TerminalStatus.of(status.status()).orElse(null);
    if (terminal == null) {
      return;
    }
    SessionStatus outcome =
        switch (terminal) {
          case COMPLETED -> SessionStatus.PAID;
          case FAILED, REJECTED -> SessionStatus.FAILED;
          case REVERSED -> null; // refunds are a later ticket; the session keeps PAID
        };
    if (outcome == null) {
      return;
    }
    ErrorInfo error = status.error();
    String errorCode = outcome == SessionStatus.FAILED ? (error == null ? "MW-SYS-001" : error.code()) : null;
    if (!sessions.finish(txnId, outcome, errorCode, clock.instant())) {
      return;
    }
    CheckoutSession s = sessions.findByTxnId(txnId).orElseThrow();
    log.atInfo()
        .addKeyValue("event", "checkout.session.finished")
        .addKeyValue("txnId", status.txnId())
        .addKeyValue("sessionId", s.sessionId().toString())
        .addKeyValue("merchantId", s.merchantId())
        .addKeyValue("status", outcome.name())
        .addKeyValue("errorCode", errorCode)
        .log("checkout session finished");
    listeners.orderedStream().forEach(l -> l.onOutcome(s));
  }
}
