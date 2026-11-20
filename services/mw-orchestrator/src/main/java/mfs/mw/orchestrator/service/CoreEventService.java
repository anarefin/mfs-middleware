package mfs.mw.orchestrator.service;

import mfs.mw.orchestrator.dto.CoreEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The core's events on Kafka (spec 6.3 as amended by ADR D31). Since ticket 95a the saga takes the
 * core's answers from its REST calls ({@link CoreReplyService}); the result events the core still
 * publishes for the same operations (TransactionValidated, FundsHeld, HoldCommitted, TransferPosted,
 * ... with a {@code <txnId>:rest:<op>} commandId) are ignored here, so no answer is applied twice.
 * Events that answer no call of the saga (HoldExpired, wallet-state changes) are ticket 95c's.
 */
@Service
public class CoreEventService {

  private static final Logger log = LoggerFactory.getLogger(CoreEventService.class);

  /** Ignores {@code event}: nothing the saga needs comes on Kafka until ticket 95c. */
  public void handle(CoreEvent event) {
    log.atDebug()
        .addKeyValue("event", "core.event.ignored")
        .addKeyValue("txnId", event.txnId())
        .addKeyValue("eventType", event.eventType())
        .log("core event ignored: the saga takes the core's answers over REST");
  }
}
