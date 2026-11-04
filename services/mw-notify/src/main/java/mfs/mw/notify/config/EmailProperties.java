package mfs.mw.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Email over SMTP (spec 10.2). The SMTP connection itself is {@code spring.mail.*} (host, port,
 * STARTTLS, timeouts); a username and password, where the server needs them, come from OpenBao
 * ({@code secret/mw-notify}, keys {@code spring.mail.username} / {@code spring.mail.password}).
 *
 * @param enabled false switches the SMTP client off; the dispatcher then fails every email send as
 *     transient
 * @param from the From address of every email
 */
@ConfigurationProperties("mw.notify.email")
public record EmailProperties(boolean enabled, String from) {

  public EmailProperties {
    from = from == null || from.isBlank() ? "upay <no-reply@upay.local>" : from;
  }
}
