package mfs.mw.notify.service;

import java.util.List;
import mfs.mw.notify.dto.InboxMessage;
import mfs.mw.notify.exception.ErrorCodes;
import mfs.mw.notify.exception.MwException;
import mfs.mw.notify.repository.InboxRepository;
import mfs.mw.notify.util.MsisdnHash;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The customer inbox (ticket 107a). Rows are only the requested MSISDN's, and OTP is omitted. */
@Service
public class InboxService {

  private final InboxRepository messages;

  public InboxService(InboxRepository messages) {
    this.messages = messages;
  }

  public InboxMessage.Page page(String msisdn, int limit, int offset) {
    String canonical = require(msisdn);
    List<InboxMessage> items = messages.page(canonical, limit, offset).stream()
        .map(r -> InboxMessage.of(r.messageId(), r.channel(), r.txnId(), r.msisdn(), r.priority(), r.body(),
            r.readAt(), r.createdAt()))
        .toList();
    return new InboxMessage.Page(items, limit, offset);
  }

  public void markRead(String msisdn, String id) {
    if (messages.markRead(require(msisdn), id) == 0) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
    }
  }

  private static String require(String msisdn) {
    String canonical = MsisdnHash.canonical(msisdn);
    if (canonical == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Not a mobile number");
    }
    return canonical;
  }
}
