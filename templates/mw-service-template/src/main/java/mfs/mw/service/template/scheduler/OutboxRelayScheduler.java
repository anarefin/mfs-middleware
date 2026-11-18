package mfs.mw.service.template.scheduler;

import mfs.mw.service.template.messaging.OutboxRelay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relays the outbox (spec 6.4): right after start-up and then {@code mw.outbox.relay.period}
 * after each run ends, it publishes every unsent row ({@link OutboxRelay#relayPending()}, which
 * keeps going while batches come back full). A scheduler thread, never a Kafka consumer thread.
 * Off when {@code mw.outbox.relay.enabled=false} (the test default).
 *
 * <p>No ShedLock here, unlike the other {@code scheduler/} jobs: every instance runs it, and the
 * relay's own transaction-scoped advisory lock lets one of them publish at a time, without a lock
 * row written twice a second.
 */
@Component
@ConditionalOnBooleanProperty(name = "mw.outbox.relay.enabled", matchIfMissing = true)
public class OutboxRelayScheduler {

  private final OutboxRelay relay;

  public OutboxRelayScheduler(OutboxRelay relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${mw.outbox.relay.period}")
  public void relay() {
    relay.relayPending();
  }
}
