package mfs.mw.recon.scheduler;

import mfs.mw.recon.messaging.OutboxRelay;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relays the outbox (spec 6.4, ADR D27): right after start-up and then {@code
 * mw.outbox.relay.period} after each run ends, it publishes every unsent row ({@link
 * OutboxRelay#relayPending()}, which keeps going while batches come back full). A scheduler
 * thread, never a Kafka consumer thread. Off when {@code mw.outbox.relay.enabled=false} (the test
 * default).
 *
 * <p>ShedLock-guarded like every {@code scheduler/} job, so one instance runs it at a time; the
 * relay's own transaction-scoped advisory lock and {@code FOR UPDATE SKIP LOCKED} keep that true
 * even for callers outside this schedule.
 */
@Component
@ConditionalOnBooleanProperty(name = "mw.outbox.relay.enabled", matchIfMissing = true)
public class OutboxRelayScheduler {

  public static final String LOCK_NAME = "outbox-relay";

  private final OutboxRelay relay;

  public OutboxRelayScheduler(OutboxRelay relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${mw.outbox.relay.period}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M")
  public void relay() {
    relay.relayPending();
  }
}
