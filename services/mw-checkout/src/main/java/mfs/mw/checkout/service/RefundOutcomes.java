package mfs.mw.checkout.service;

import java.time.Clock;
import java.util.UUID;
import mfs.mw.checkout.dto.ErrorInfo;
import mfs.mw.checkout.dto.StatusMessage;
import mfs.mw.checkout.dto.TerminalStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.entity.Refund;
import mfs.mw.checkout.mapper.RefundMapper;
import mfs.mw.checkout.repository.IpnDeliveryRepository;
import mfs.mw.checkout.repository.RefundRepository;
import mfs.mw.checkout.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a refund's outcome lands (ticket 116): PENDING -> COMPLETED for a COMPLETED REVERSAL,
 * PENDING -> REJECTED for REJECTED/FAILED, from the refund request within its budget or from the
 * {@code mw.txn.status.v1} projection; the first wins. In the same transaction attempt 1 of the
 * signed refund IPN is queued, so an outcome never commits without its IPN.
 */
@Service
public class RefundOutcomes {

  private static final Logger log = LoggerFactory.getLogger(RefundOutcomes.class);

  private final RefundRepository refunds;
  private final SessionRepository sessions;
  private final IpnDeliveryRepository deliveries;
  private final RefundMapper mapper;
  private final IpnRetrySchedule schedule;
  private final Clock clock;

  public RefundOutcomes(
      RefundRepository refunds, SessionRepository sessions, IpnDeliveryRepository deliveries, RefundMapper mapper,
      IpnRetrySchedule schedule, Clock clock) {
    this.refunds = refunds;
    this.sessions = sessions;
    this.deliveries = deliveries;
    this.mapper = mapper;
    this.schedule = schedule;
    this.clock = clock;
  }

  /**
   * The projection path: applies a final status of a refund transaction; a duplicate answer and
   * anything else is ignored.
   */
  @Transactional
  public void apply(StatusMessage status) {
    if (status.duplicate()) {
      return;
    }
    settle(status, uuid(status.txnId()));
  }

  /**
   * The request path: the answer to the REVERSAL sent for {@code sentRefundId}. A duplicate answer (a
   * repeated key) settles it only when it describes that very refund, i.e. its stored txnId is the
   * refundId sent (a retry resends the PENDING refund under its own refundId, M9 review FL-06); a
   * duplicate naming another transaction settles nothing and the refund stays PENDING (security review).
   */
  @Transactional
  public void applyAnswer(StatusMessage status, UUID sentRefundId) {
    UUID stored = uuid(status.txnId());
    if (stored == null || !stored.equals(sentRefundId)) {
      return;
    }
    settle(status, stored);
  }

  private void settle(StatusMessage status, UUID refundId) {
    if (!status.terminal() || refundId == null) {
      return;
    }
    Refund.Status outcome =
        switch (TerminalStatus.of(status.status()).orElseThrow()) {
          case COMPLETED, REVERSED -> Refund.Status.COMPLETED;
          case FAILED, REJECTED -> Refund.Status.REJECTED;
        };
    ErrorInfo error = status.error();
    String errorCode = outcome == Refund.Status.REJECTED ? (error == null ? "MW-SYS-001" : error.code()) : null;
    if (!refunds.finish(refundId, outcome, errorCode, clock.instant())) {
      return;
    }
    Refund r = refunds.findById(refundId).orElseThrow();
    CheckoutSession s = sessions.findById(r.sessionId()).orElseThrow();
    log.atInfo()
        .addKeyValue("event", "checkout.refund.finished")
        .addKeyValue("txnId", status.txnId())
        .addKeyValue("sessionId", s.sessionId().toString())
        .addKeyValue("merchantId", s.merchantId())
        .addKeyValue("status", outcome.name())
        .addKeyValue("errorCode", errorCode)
        .log("checkout refund finished");
    deliveries.insert(mapper.firstIpnAttempt(r, s, schedule.firstAttemptAt()));
  }

  private static UUID uuid(String id) {
    try {
      return id == null ? null : UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
