package mfs.mw.core.mock.service;

import java.util.Collection;
import java.util.List;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.entity.WalletState;
import mfs.mw.core.mock.mapper.CoreEventMapper;
import mfs.mw.core.mock.messaging.EventMetadata;
import mfs.mw.core.mock.messaging.OutboxWriter;
import mfs.mw.core.mock.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code WalletStateChanged} on {@code core.wallet.event.v1} (ticket 67, spec 18.7, D5): the feed of
 * mw-auth's wallet projection. Written to the outbox in the transaction that created the wallet or
 * changed its status, keyed by walletId (one wallet's events stay in order on one partition), with
 * the wallet's version from the row. Wallets without an MSISDN (system wallets) are not published.
 */
@Service
public class WalletStateEvents {

  public static final String TOPIC = CoreCommandService.EVENT_TOPIC;

  private static final Logger log = LoggerFactory.getLogger(WalletStateEvents.class);

  private final WalletRepository wallets;
  private final CoreEventMapper events;
  private final OutboxWriter outbox;

  public WalletStateEvents(WalletRepository wallets, CoreEventMapper events, OutboxWriter outbox) {
    this.wallets = wallets;
    this.events = events;
    this.outbox = outbox;
  }

  /** The wallets were just created (in the caller's transaction). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void created(Collection<String> walletIds) {
    for (String id : walletIds) {
      wallets.state(id).ifPresent(w -> append(w, "CREATED"));
    }
  }

  /** The wallet's status just changed (in the caller's transaction). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void statusChanged(String walletId) {
    wallets.state(walletId).ifPresent(w -> append(w, "STATUS_CHANGED"));
  }

  /**
   * Publishes every wallet at its current version (dev, on start: {@code
   * mw.coremock.wallet-snapshot-on-start}); consumers ignore what they already hold. Returns the
   * number of events.
   */
  @Transactional
  public int snapshot() {
    List<WalletState> all = wallets.states();
    all.forEach(w -> append(w, "SNAPSHOT"));
    log.atInfo()
        .addKeyValue("event", "coremock.wallet.snapshot")
        .addKeyValue("count", all.size())
        .log("wallet state snapshot published");
    return all.size();
  }

  private void append(WalletState w, String change) {
    CoreEvent e = events.walletStateChanged(w, change);
    outbox.append(TOPIC, w.id(), new EventMetadata(e.txnId(), e.txnId(), e.eventId(), 1), e);
  }
}
