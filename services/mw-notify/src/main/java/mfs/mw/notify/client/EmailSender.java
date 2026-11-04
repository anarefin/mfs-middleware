package mfs.mw.notify.client;

import mfs.mw.notify.dto.EmailMessage;
import mfs.mw.notify.exception.EmailSendException;

/** Sends one email to the SMTP server (spec 10.2). */
public interface EmailSender {

  /** @return the Message-ID the email went out with */
  String send(EmailMessage email) throws EmailSendException;
}
