package mfs.mw.app.gateway.service;

import mfs.mw.app.gateway.client.NotifyInboxClient;
import mfs.mw.app.gateway.client.NotifyNotFoundException;
import mfs.mw.app.gateway.client.NotifyUnavailableException;
import mfs.mw.app.gateway.dto.InboxMessage;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** The signed-in wallet's inbox, read from mw-notify (ticket 107a). The MSISDN is always the token's. */
@Service
public class CustomerInboxService {

  private final NotifyInboxClient notify;

  public CustomerInboxService(NotifyInboxClient notify) {
    this.notify = notify;
  }

  public InboxMessage.Page page(String msisdn, int limit, int offset) {
    try {
      return notify.page(msisdn, limit, offset);
    } catch (NotifyNotFoundException e) {
      throw missing();
    } catch (NotifyUnavailableException e) {
      throw unavailable(e);
    }
  }

  public void markRead(String msisdn, String id) {
    try {
      notify.markRead(msisdn, id);
    } catch (NotifyNotFoundException e) {
      throw missing();
    } catch (NotifyUnavailableException e) {
      throw unavailable(e);
    }
  }

  private static MwException missing() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
  }

  private static MwException unavailable(NotifyUnavailableException e) {
    return new MwException(ErrorCodes.SYS_NOTIFY_UNAVAILABLE, "Notifications cannot be asked", e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
