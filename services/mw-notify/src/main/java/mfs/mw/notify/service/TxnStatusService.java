package mfs.mw.notify.service;

import java.util.List;
import java.util.Optional;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.repository.ContactRepository;
import mfs.mw.notify.repository.RuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds notifications from {@code mw.txn.status.v1} by the rules in {@code notify.rule} (spec
 * 10.3) and accepts them like any other request, in one transaction. Rule-built messageIds are
 * deterministic, so a redelivered status event creates nothing new. EMAIL rules go to the
 * recipient party's address in {@code notify.contact}, if it has one; PUSH rules (ticket 110) to the
 * device token its wallet's app registered, if any (FL-14: SMS always, push optional).
 */
@Service
public class TxnStatusService {

  public static final String TOPIC = "mw.txn.status.v1";

  private final RuleRepository rules;
  private final RuleEngine engine;
  private final NotificationService notifications;
  private final ContactRepository contacts;
  private final PushDeviceService pushDevices;

  public TxnStatusService(RuleRepository rules, RuleEngine engine, NotificationService notifications,
      ContactRepository contacts, PushDeviceService pushDevices) {
    this.pushDevices = pushDevices;
    this.rules = rules;
    this.contacts = contacts;
    this.engine = engine;
    this.notifications = notifications;
  }

  /** @return the number of new notifications */
  @Transactional
  public int onStatus(TxnStatusEvent event) {
    RuleEngine.ContactLookup lookup = new RuleEngine.ContactLookup() {
      @Override
      public Optional<String> emailOf(String partyId) {
        return contacts.emailOf(partyId);
      }

      @Override
      public Optional<String> pushTokenOf(String partyId) {
        return pushDevices.tokenOf(partyId);
      }
    };
    List<NotificationRequest> requests = engine.requestsFor(event, rules.enabledFor(TOPIC), lookup);
    int accepted = 0;
    for (NotificationRequest r : requests) {
      if (notifications.accept(r)) {
        accepted++;
      }
    }
    return accepted;
  }
}
