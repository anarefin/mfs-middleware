package mfs.mw.notify.client;

import mfs.mw.notify.dto.EmailMessage;
import mfs.mw.notify.exception.EmailSendException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** With {@code mw.notify.email.enabled=false} there is no SMTP server: every send is a transient error. */
@Component
@ConditionalOnProperty(name = "mw.notify.email.enabled", havingValue = "false")
public class DisabledEmailSender implements EmailSender {

  @Override
  public String send(EmailMessage email) throws EmailSendException {
    throw new EmailSendException(true, -1, "SMTP client disabled");
  }
}
