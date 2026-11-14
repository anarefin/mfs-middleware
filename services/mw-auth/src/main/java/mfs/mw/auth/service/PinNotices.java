package mfs.mw.auth.service;

import mfs.mw.auth.config.DeviceBindingProperties;
import mfs.mw.auth.dto.NotificationRequest;
import mfs.mw.auth.mapper.DeviceNotificationMapper;
import mfs.mw.auth.messaging.EventMetadata;
import mfs.mw.auth.messaging.OutboxWriter;
import org.springframework.stereotype.Service;

/**
 * The SMS of the PIN lifecycle (ticket 70) through the outbox, so each commits with its change:
 * {@code PIN_CHANGED} after a change or a reset, {@code PIN_RESET_APPROVED} when a care reset is
 * approved. Call inside the change's transaction. No parameters, so nothing secret.
 */
@Service
public class PinNotices {

  private final DeviceNotificationMapper notifications;
  private final OutboxWriter outbox;
  private final DeviceBindingProperties props;

  public PinNotices(DeviceNotificationMapper notifications, OutboxWriter outbox, DeviceBindingProperties props) {
    this.notifications = notifications;
    this.outbox = outbox;
    this.props = props;
  }

  public void pinChanged(String msisdn) {
    send(msisdn, DeviceNotificationMapper.PIN_CHANGED_TEMPLATE, null);
  }

  public void resetApproved(String msisdn, String locale) {
    send(msisdn, DeviceNotificationMapper.RESET_APPROVED_TEMPLATE, locale);
  }

  private void send(String msisdn, String template, String locale) {
    String correlationId = DeviceOtpService.correlationId();
    NotificationRequest n = notifications.notice(msisdn, template, locale, correlationId);
    outbox.append(props.notifyTopic(), msisdn, new EventMetadata(correlationId, n.messageId(), n.messageId(), 1), n);
  }
}
