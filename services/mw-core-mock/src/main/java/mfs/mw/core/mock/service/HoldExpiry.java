package mfs.mw.core.mock.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.entity.Hold;
import mfs.mw.core.mock.mapper.CoreEventMapper;
import mfs.mw.core.mock.messaging.EventMetadata;
import mfs.mw.core.mock.messaging.OutboxWriter;
import mfs.mw.core.mock.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hold expiry (ticket 94, FRS 7.3): a hold not committed or released within {@code mw.coremock.hold-ttl}
 * (default 30 min) is released by the core: its reservation returns to the available balance, the hold
 * turns EXPIRED and {@code HoldExpired} goes to {@code core.payment.event.v1} through the outbox, in one
 * transaction per hold, so it is published exactly once. A later commit fails {@code HOLD_EXPIRED}, a
 * release succeeds without effect. Run by {@code scheduler/HoldExpiryJob}.
 */
@Service
public class HoldExpiry {

  private static final Logger log = LoggerFactory.getLogger(HoldExpiry.class);

  private final HoldRepository holds;
  private final HoldOperations holdOps;
  private final CoreEventMapper events;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final int batchSize;

  public HoldExpiry(HoldRepository holds, HoldOperations holdOps, CoreEventMapper events, OutboxWriter outbox,
      TransactionTemplate tx, Clock clock, CoreMockProperties props) {
    this.holds = holds;
    this.holdOps = holdOps;
    this.events = events;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
    this.batchSize = props.holdExpiry().batchSize();
  }

  /** Releases the holds that are due (at most one batch); returns how many expired. */
  public int expireDue() {
    List<String> due = holds.dueForExpiry(clock.instant(), batchSize);
    int expired = 0;
    for (String id : due) {
      Boolean done = tx.execute(status -> expire(id));
      if (Boolean.TRUE.equals(done)) {
        expired++;
      }
    }
    if (expired > 0) {
      log.atInfo().addKeyValue("event", "core.hold.expired_batch").addKeyValue("count", expired)
          .log("expired holds released");
    }
    return expired;
  }

  private boolean expire(String holdId) {
    Optional<Hold> expired = holdOps.expire(holdId);
    if (expired.isEmpty()) {
      return false;
    }
    Hold hold = expired.get();
    CoreEvent e = events.holdExpired(hold, List.of(holdOps.balanceAfter(hold.walletId())));
    outbox.append(CoreCommandService.PAYMENT_EVENT_TOPIC, hold.txnId(), new EventMetadata(hold.txnId(), hold.txnId(),
        e.commandId(), 1), e);
    log.atInfo().addKeyValue("event", "core.hold.expired").addKeyValue("txnId", hold.txnId())
        .log("hold expired and released");
    return true;
  }
}
