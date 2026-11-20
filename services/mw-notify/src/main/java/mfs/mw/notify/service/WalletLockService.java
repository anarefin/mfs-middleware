package mfs.mw.notify.service;

import java.util.List;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.WalletLockEvent;
import mfs.mw.notify.repository.RuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lock and unlock notices from {@code auth.wallet-lock.v1} (ticket 111, NOT-01). The MANUAL_REVIEW
 * ops alert stays on {@code mw.txn.status.v1} (the rules of V16). PIN_RESET and REVOKE_SESSIONS
 * match no rule.
 */
@Service
public class WalletLockService {

  public static final String TOPIC = "auth.wallet-lock.v1";

  private final RuleRepository rules;
  private final RuleEngine engine;
  private final NotificationService notifications;

  public WalletLockService(RuleRepository rules, RuleEngine engine, NotificationService notifications) {
    this.rules = rules;
    this.engine = engine;
    this.notifications = notifications;
  }

  /** @return the number of new notifications */
  @Transactional
  public int onCommand(WalletLockEvent event) {
    List<NotificationRequest> requests = engine.requestsFor(event, rules.enabledFor(TOPIC));
    int accepted = 0;
    for (NotificationRequest r : requests) {
      if (notifications.accept(r)) {
        accepted++;
      }
    }
    return accepted;
  }
}
