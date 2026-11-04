package mfs.mw.notify.service;

import java.util.List;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.repository.RuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds notifications from {@code mw.txn.status.v1} by the rules in {@code notify.rule} (spec
 * 10.3) and accepts them like any other request, in one transaction. Rule-built messageIds are
 * deterministic, so a redelivered status event creates nothing new.
 */
@Service
public class TxnStatusService {

  public static final String TOPIC = "mw.txn.status.v1";

  private final RuleRepository rules;
  private final RuleEngine engine;
  private final NotificationService notifications;

  public TxnStatusService(RuleRepository rules, RuleEngine engine, NotificationService notifications) {
    this.rules = rules;
    this.engine = engine;
    this.notifications = notifications;
  }

  /** @return the number of new notifications */
  @Transactional
  public int onStatus(TxnStatusEvent event) {
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
