package mfs.mw.notify.service;

import java.util.List;
import mfs.mw.notify.dto.NotificationLogItem;
import mfs.mw.notify.dto.NotificationLogQuery;
import mfs.mw.notify.repository.NotificationLogRepository;
import mfs.mw.notify.util.MsisdnHash;
import org.springframework.stereotype.Service;

/** The masked notification log (ticket 111, NOT-08). */
@Service
public class NotificationLogService {

  private final NotificationLogRepository messages;

  public NotificationLogService(NotificationLogRepository messages) {
    this.messages = messages;
  }

  public NotificationLogItem.Page search(NotificationLogQuery query) {
    String msisdn = query.msisdn() == null ? null : MsisdnHash.canonical(query.msisdn());
    NotificationLogQuery stored = new NotificationLogQuery(msisdn, blankToNull(query.txnId()),
        blankToNull(query.channel()), blankToNull(query.status()), query.from(), query.to(), query.limit(),
        query.offset());
    List<NotificationLogItem> items = messages.search(stored).stream()
        .map(r -> NotificationLogItem.of(r.messageId(), r.txnId(), r.channel(), r.msisdn(), r.priority(), r.status(),
            r.templateCode(), r.body(), r.createdAt()))
        .toList();
    return new NotificationLogItem.Page(items, query.limit(), query.offset());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
