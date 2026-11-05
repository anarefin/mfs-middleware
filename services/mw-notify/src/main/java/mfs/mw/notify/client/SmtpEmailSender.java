package mfs.mw.notify.client;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import mfs.mw.notify.config.EmailProperties;
import mfs.mw.notify.dto.EmailMessage;
import mfs.mw.notify.exception.EmailSendException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Email over SMTP with Spring's {@link JavaMailSender} (spec 10.2; connection from {@code
 * spring.mail.*}, STARTTLS when the server offers it). Each email is multipart/alternative with a
 * UTF-8 text part and, when the template has one, an HTML part. A 5xx SMTP reply or a message that
 * cannot be built is permanent; no connection, timeouts and 4xx replies are transient.
 */
@Component
@ConditionalOnProperty(name = "mw.notify.email.enabled", havingValue = "true", matchIfMissing = true)
public class SmtpEmailSender implements EmailSender {

  private final JavaMailSender mail;
  private final EmailProperties props;

  public SmtpEmailSender(JavaMailSender mail, EmailProperties props) {
    this.mail = mail;
    this.props = props;
  }

  @Override
  public String send(EmailMessage email) throws EmailSendException {
    MimeMessage mime = mail.createMimeMessage();
    try {
      MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
      helper.setFrom(props.from());
      helper.setTo(email.to());
      helper.setSubject(email.subject());
      if (email.html() != null) {
        // MimeMessageHelper's multipart modes nest the alternative in multipart/mixed (or
        // related); a receipt has no attachments, so it is a top-level multipart/alternative of
        // the text part then the HTML part (the client shows the last one it can render).
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(email.text(), StandardCharsets.UTF_8.name());
        MimeBodyPart html = new MimeBodyPart();
        html.setText(email.html(), StandardCharsets.UTF_8.name(), "html");
        alternative.addBodyPart(text);
        alternative.addBodyPart(html);
        mime.setContent(alternative);
      } else {
        helper.setText(email.text(), false);
      }
    } catch (MessagingException e) {
      throw new EmailSendException(false, -1, "email could not be built: " + e.getClass().getSimpleName());
    }
    try {
      mail.send(mime);
      return mime.getMessageID();
    } catch (MailParseException | MailPreparationException e) {
      throw new EmailSendException(false, -1, "email could not be built: " + e.getClass().getSimpleName());
    } catch (MailSendException e) {
      int code = smtpCode(e);
      throw new EmailSendException(code < 500, code, "SMTP send failed" + (code > 0 ? " (" + code + ")" : ""));
    } catch (MailException e) {
      throw new EmailSendException(true, -1, "SMTP send failed: " + e.getClass().getSimpleName());
    } catch (MessagingException e) {
      throw new EmailSendException(true, -1, "SMTP send failed: " + e.getClass().getSimpleName());
    }
  }

  /** The first SMTP reply code found among the failure's causes, or -1. */
  static int smtpCode(MailSendException e) {
    Deque<Throwable> todo = new ArrayDeque<>();
    todo.add(e);
    todo.addAll(e.getFailedMessages().values());
    Set<Throwable> seen = new HashSet<>();
    while (!todo.isEmpty()) {
      Throwable t = todo.poll();
      if (t == null || !seen.add(t)) {
        continue;
      }
      switch (t) {
        case SMTPAddressFailedException a -> {
          return a.getReturnCode();
        }
        case SMTPSendFailedException s -> {
          return s.getReturnCode();
        }
        case SMTPSenderFailedException s -> {
          return s.getReturnCode();
        }
        default -> {
          // keep looking
        }
      }
      if (t instanceof MessagingException m && m.getNextException() != null) {
        todo.add(m.getNextException());
      }
      if (t.getCause() != null) {
        todo.add(t.getCause());
      }
    }
    return -1;
  }
}
