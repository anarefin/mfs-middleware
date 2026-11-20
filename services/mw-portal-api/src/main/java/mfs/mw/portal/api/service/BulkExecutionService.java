package mfs.mw.portal.api.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import mfs.mw.portal.api.config.PortalApiProperties;
import mfs.mw.portal.api.dto.StatusMessage;
import mfs.mw.portal.api.dto.TxnRequest;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.entity.ItemStatus;
import mfs.mw.portal.api.exception.CoreUnavailableException;
import mfs.mw.portal.api.entity.PendingChild;
import mfs.mw.portal.api.mapper.ChildRequestMapper;
import mfs.mw.portal.api.messaging.EventMetadata;
import mfs.mw.portal.api.messaging.OutboxWriter;
import mfs.mw.portal.api.repository.BatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Execution of approved bulk batches (spec 9.4, WP22, ticket 34).
 *
 * <p>{@link #dispatch()} streams child txns to the orchestrator at {@code bulk.dispatch.rate-per-
 * second} over all running batches: a token bucket filled at that rate, holding at most one period's
 * worth, so an idle dispatcher never saves up a burst. Each run locks that many unsent rows, writes
 * one {@code mw.txn.request.v1} record per row to the outbox and marks the rows SUBMITTED, all in
 * one transaction: a crash leaves a row either unsent (sent later, with the same derived txnId and
 * key) or sent and recorded. Either way the orchestrator's idempotency answers a repeat with the
 * stored transaction, so no row is paid twice.
 *
 * <p>{@link #apply(StatusMessage)} settles a row from its child's status record and completes the
 * batch once no row is unsent or running.
 */
@Service
public class BulkExecutionService {

  static final String REQUEST_TOPIC = "mw.txn.request.v1";

  private static final Logger log = LoggerFactory.getLogger(BulkExecutionService.class);

  private final BatchRepository batches;
  private final FundsCheck funds;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final double ratePerSecond;
  private final double capacity;

  private double tokens;
  private Instant last;

  public BulkExecutionService(
      BatchRepository batches, FundsCheck funds, OutboxWriter outbox, TransactionTemplate tx, PortalApiProperties props,
      Clock clock) {
    this.batches = batches;
    this.funds = funds;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
    PortalApiProperties.Dispatch d = props.bulk().dispatch();
    this.ratePerSecond = d.ratePerSecond();
    this.capacity = Math.max(1.0, Math.ceil(d.ratePerSecond() * seconds(d.period())));
  }

  /**
   * Starts the APPROVED batches whose org wallet still holds what they pay (ticket 129c), then
   * sends what the rate allows since the last run; returns the number of children sent.
   */
  public synchronized int dispatch() {
    startApproved();
    Instant now = clock.instant();
    if (last != null) {
      double elapsed = Math.max(0, seconds(Duration.between(last, now)));
      tokens = Math.min(capacity, tokens + elapsed * ratePerSecond);
    }
    last = now;
    int allowed = (int) Math.floor(tokens);
    if (allowed == 0) {
      return 0;
    }
    Integer sent = tx.execute(status -> send(allowed, now));
    int n = sent == null ? 0 : sent;
    tokens -= n;
    return n;
  }

  /**
   * The balance check at dispatch start (ticket 129c): each APPROVED batch is read against the core
   * once; enough funds, it becomes EXECUTING (its rows are sent); short, BLOCKED_FUNDS with the
   * shortfall, nothing sent. No balance from the core leaves it APPROVED for the next run.
   */
  private void startApproved() {
    for (BatchRepository.Unstarted b : batches.unstarted()) {
      OptionalLong shortfall;
      try {
        shortfall = funds.shortfall(b.orgWallet(), b.pendingMinor());
      } catch (CoreUnavailableException e) {
        log.atWarn()
            .addKeyValue("event", "bulk.batch.balance_unavailable")
            .addKeyValue("batchId", b.batchId())
            .log("Bulk batch not started: the core gave no org wallet balance");
        continue;
      }
      if (shortfall.isEmpty()) {
        batches.start(b.batchId());
      } else if (batches.blockFunds(
          b.batchId(), BatchStatus.APPROVED, null, shortfall.getAsLong(), FundsCheck.INSUFFICIENT_BALANCE)) {
        log.atWarn()
            .addKeyValue("event", "bulk.batch.blocked_funds")
            .addKeyValue("batchId", b.batchId())
            .addKeyValue("errorCode", FundsCheck.INSUFFICIENT_BALANCE)
            .log("Bulk batch blocked at dispatch start: the org wallet holds less than the batch");
      }
    }
  }

  /** Forgets the bucket (tests start every case from an empty one). */
  public synchronized void resetThrottle() {
    tokens = 0;
    last = null;
  }

  private int send(int limit, Instant now) {
    List<PendingChild> rows = batches.lockPending(limit);
    if (rows.isEmpty()) {
      return 0;
    }
    List<UUID> txnIds = new ArrayList<>(rows.size());
    Set<UUID> batchIds = new LinkedHashSet<>();
    for (PendingChild row : rows) {
      TxnRequest req = ChildRequestMapper.toRequest(row, now);
      outbox.append(
          REQUEST_TOPIC, req.txnId(), new EventMetadata(req.correlationId(), req.txnId(), req.idempotencyKey(), 1), req);
      txnIds.add(UUID.fromString(req.txnId()));
      batchIds.add(row.batchId());
    }
    batches.markSubmitted(rows, txnIds, now);
    batches.markExecuting(batchIds);
    log.atDebug()
        .addKeyValue("event", "bulk.children.sent")
        .addKeyValue("count", rows.size())
        .log("bulk children sent to the orchestrator");
    return rows.size();
  }

  /**
   * Applies one {@code mw.txn.status.v1} record: a settled state of a bulk child settles its row
   * (in-progress states, other channels and unknown txns are skipped); a row that changed may
   * complete its batch. Redelivered or stale records change nothing.
   */
  public void apply(StatusMessage s) {
    if (!ChildRequestMapper.CHANNEL.equals(s.channel())
        || !ChildRequestMapper.TXN_TYPE.equals(s.txnType())
        || s.txnId() == null) {
      return;
    }
    Optional<ItemStatus> settled = ItemStatus.settledBy(s.status());
    if (settled.isEmpty()) {
      return;
    }
    UUID txnId;
    try {
      txnId = UUID.fromString(s.txnId());
    } catch (IllegalArgumentException e) {
      return;
    }
    settle(txnId, settled.get(), s.error() == null ? null : s.error().code());
  }

  /**
   * Settles the row of child {@code txnId} (the status record's rules: final states stay) and
   * completes its batch once nothing is unsent or running. Returns whether the row changed.
   */
  public boolean settle(UUID txnId, ItemStatus status, String errorCode) {
    Instant now = clock.instant();
    Boolean changed =
        tx.execute(
            t -> {
              Optional<UUID> batchId = batches.settle(txnId, status, errorCode, now);
              batchId.ifPresent(id -> complete(id, now));
              return batchId.isPresent();
            });
    return Boolean.TRUE.equals(changed);
  }

  /** Gives up a SUBMITTED row (UNRESOLVED) and completes its batch if that was the last one open. */
  public boolean giveUp(UUID txnId) {
    Instant now = clock.instant();
    Boolean changed =
        tx.execute(
            t -> {
              Optional<UUID> batchId = batches.markUnresolved(txnId, now);
              batchId.ifPresent(id -> complete(id, now));
              return batchId.isPresent();
            });
    return Boolean.TRUE.equals(changed);
  }

  private void complete(UUID batchId, Instant now) {
    batches
        .completeIfSettled(batchId, now)
        .ifPresent(
            st ->
                log.atInfo()
                    .addKeyValue("event", "bulk.batch.completed")
                    .addKeyValue("batchId", batchId)
                    .addKeyValue("status", st.name())
                    .log("Bulk batch " + st.name() + ": every row settled"));
  }

  private static double seconds(Duration d) {
    return d.toNanos() / 1e9;
  }
}
