package mfs.mw.notify.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import mfs.mw.notify.config.EmailProperties;
import mfs.mw.notify.dto.EmailMessage;
import mfs.mw.notify.exception.EmailSendException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * SMTP failures sorted into transient and permanent (spec 10.4): a 5xx reply or a message that
 * cannot be built is permanent; no connection, authentication trouble and 4xx replies are
 * transient. The SMTP server's answer is played by a {@link JavaMailSenderImpl} whose send throws
 * what Spring would throw for it; "no connection" is a real connection to a closed port.
 */
class SmtpEmailSenderTest {

  private static final EmailProperties PROPS = new EmailProperties(true, "upay <no-reply@upay.local>");
  private static final EmailMessage EMAIL =
      new EmailMessage("customer@example.test", "Bill paid", "Tk 500.00 paid", "<p>Tk 500.00 paid</p>");

  /** A mail sender whose SMTP server answers every send with {@code failure}. */
  private static JavaMailSenderImpl answering(Supplier<RuntimeException> failure) {
    return new JavaMailSenderImpl() {
      @Override
      public void send(MimeMessage mimeMessage) {
        throw failure.get();
      }
    };
  }

  private static EmailSendException failureOf(JavaMailSenderImpl mail, EmailMessage email) {
    return catchThrowableOfType(EmailSendException.class, () -> new SmtpEmailSender(mail, PROPS).send(email));
  }

  private static MailSendException refusedRecipient(int code) throws Exception {
    InternetAddress to = new InternetAddress("customer@example.test");
    SMTPAddressFailedException address = new SMTPAddressFailedException(to, "RCPT TO", code, code + " no such user");
    SendFailedException send = new SendFailedException("Invalid Addresses", address);
    return new MailSendException(Map.of(new Object(), send));
  }

  /** A mail sender that keeps the message instead of sending it. */
  private static final class Capturing extends JavaMailSenderImpl {
    MimeMessage sent;

    @Override
    public void send(MimeMessage mimeMessage) {
      sent = mimeMessage;
    }
  }

  @Test
  void aReceiptWithHtmlIsOneMultipartAlternativeOfTextThenHtml() throws Exception {
    Capturing mail = new Capturing();
    new SmtpEmailSender(mail, PROPS).send(EMAIL);

    mail.sent.saveChanges();
    assertThat(mail.sent.getContentType()).startsWith("multipart/alternative");
    jakarta.mail.Multipart parts = (jakarta.mail.Multipart) mail.sent.getContent();
    assertThat(parts.getCount()).isEqualTo(2);
    assertThat(parts.getBodyPart(0).getContentType()).startsWith("text/plain").containsIgnoringCase("utf-8");
    assertThat(parts.getBodyPart(0).getContent()).isEqualTo("Tk 500.00 paid");
    assertThat(parts.getBodyPart(1).getContentType()).startsWith("text/html").containsIgnoringCase("utf-8");
    assertThat(parts.getBodyPart(1).getContent()).isEqualTo("<p>Tk 500.00 paid</p>");
    assertThat(mail.sent.getSubject()).isEqualTo("Bill paid");
  }

  @Test
  void aReceiptWithoutHtmlIsPlainText() throws Exception {
    Capturing mail = new Capturing();
    new SmtpEmailSender(mail, PROPS).send(new EmailMessage("customer@example.test", "Bill paid", "Tk 500.00 paid", null));

    mail.sent.saveChanges();
    assertThat(mail.sent.getContentType()).startsWith("text/plain");
    assertThat(mail.sent.getContent()).isEqualTo("Tk 500.00 paid");
  }

  @Test
  void aRefusedRecipient5xxIsPermanentWithItsReplyCode() throws Exception {
    MailSendException refused = refusedRecipient(550);

    EmailSendException e = failureOf(answering(() -> refused), EMAIL);

    assertThat(e.isTransient()).isFalse();
    assertThat(e.smtpCode()).isEqualTo(550);
    assertThat(e.getMessage()).isEqualTo("SMTP send failed (550)");
  }

  @Test
  void aTemporary4xxReplyIsTransient() throws Exception {
    MailSendException busy = refusedRecipient(451);

    EmailSendException e = failureOf(answering(() -> busy), EMAIL);

    assertThat(e.isTransient()).isTrue();
    assertThat(e.smtpCode()).isEqualTo(451);
  }

  @Test
  void aRefusedMessageOrSenderCarriesTheReplyCodeFromTheCauseChain() throws Exception {
    SMTPSendFailedException data = new SMTPSendFailedException("DATA", 554, "554 message refused", null, null,
        null, null);
    MessagingException wrapped = new MessagingException("send failed", data);
    MailSendException message = new MailSendException("send failed", wrapped);
    SMTPSenderFailedException sender =
        new SMTPSenderFailedException(new InternetAddress("no-reply@upay.local"), "MAIL FROM", 421, "421 try later");
    MailSendException from = new MailSendException("send failed", sender);

    assertThat(SmtpEmailSender.smtpCode(message)).isEqualTo(554);
    assertThat(SmtpEmailSender.smtpCode(from)).isEqualTo(421);
    assertThat(failureOf(answering(() -> message), EMAIL).isTransient()).isFalse();
    assertThat(failureOf(answering(() -> from), EMAIL).isTransient()).isTrue();
  }

  @Test
  void aSendFailureWithoutAReplyCodeIsTransient() {
    EmailSendException e = failureOf(answering(() -> new MailSendException("connection reset")), EMAIL);

    assertThat(e.isTransient()).isTrue();
    assertThat(e.smtpCode()).isEqualTo(-1);
    assertThat(e.getMessage()).isEqualTo("SMTP send failed");
  }

  @Test
  void noSmtpServerListeningIsTransient() throws IOException {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    JavaMailSenderImpl mail = new JavaMailSenderImpl();
    mail.setHost("127.0.0.1");
    mail.setPort(port);
    Properties p = new Properties();
    p.setProperty("mail.smtp.connectiontimeout", "1000");
    p.setProperty("mail.smtp.timeout", "1000");
    mail.setJavaMailProperties(p);

    EmailSendException e = failureOf(mail, EMAIL);

    assertThat(e.isTransient()).isTrue();
    assertThat(e.smtpCode()).isEqualTo(-1);
  }

  @Test
  void anAuthenticationFailureIsTransient() {
    EmailSendException e = failureOf(answering(() -> new MailAuthenticationException("535 bad credentials")), EMAIL);

    assertThat(e.isTransient()).isTrue();
    assertThat(e.getMessage()).isEqualTo("SMTP send failed: MailAuthenticationException");
  }

  @Test
  void aMessageSpringCannotParseIsPermanent() {
    EmailSendException e = failureOf(answering(() -> new MailParseException("bad header")), EMAIL);

    assertThat(e.isTransient()).isFalse();
    assertThat(e.getMessage()).startsWith("email could not be built");
  }

  @Test
  void anInvalidRecipientAddressIsPermanentAndNothingIsSent() {
    JavaMailSenderImpl mail = answering(() -> new IllegalStateException("must not send"));

    EmailSendException e = failureOf(mail, new EmailMessage("<customer@example.test", "s", "t", null));

    assertThat(e.isTransient()).isFalse();
    assertThat(e.getMessage()).startsWith("email could not be built");
  }

  @Test
  void theDisabledSenderFailsEverySendAsTransient() {
    EmailSendException e = catchThrowableOfType(EmailSendException.class, () -> new DisabledEmailSender().send(EMAIL));

    assertThat(e.isTransient()).isTrue();
    assertThat(e.smtpCode()).isEqualTo(-1);
  }
}
