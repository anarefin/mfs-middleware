package mfs.mw.notify.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import mfs.mw.notify.config.NotifyProperties;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.entity.TemplateRecord;
import mfs.mw.notify.exception.ErrorCodes;
import mfs.mw.notify.exception.TemplateException;
import mfs.mw.notify.repository.MessageRepository;
import mfs.mw.notify.repository.TemplateRepository;
import mfs.mw.notify.util.MaskingPolicy;
import mfs.mw.notify.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a {@link NotificationRequest} (spec 10.1): drops a repeated {@code messageId}, records a
 * message already past {@code expiresAt} (default by priority) as EXPIRED, renders the template
 * with masked params and queues it for the dispatcher. The MVP sends SMS only; a request without
 * SMS to an MSISDN, or whose template cannot be rendered, is recorded as FAILED. Nothing is sent
 * here, so a Kafka listener thread never waits on the SMSC.
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final MessageRepository messages;
  private final TemplateRepository templates;
  private final TemplateRenderer renderer;
  private final StatusEvents statuses;
  private final NotifyProperties props;
  private final Clock clock;

  public NotificationService(MessageRepository messages, TemplateRepository templates, TemplateRenderer renderer,
      StatusEvents statuses, NotifyProperties props, Clock clock) {
    this.messages = messages;
    this.templates = templates;
    this.renderer = renderer;
    this.statuses = statuses;
    this.props = props;
    this.clock = clock;
  }

  /** @return false when the messageId was already accepted (duplicate dropped) */
  @Transactional
  public boolean accept(NotificationRequest r) {
    Instant now = clock.instant();
    Priority priority = r.priority() == null ? Priority.TXN : r.priority();
    Instant expiresAt = r.expiresAt() != null ? r.expiresAt() : now.plus(priority.defaultTtl());
    String msisdn = r.recipient() == null ? null : r.recipient().msisdn();
    String locale = r.locale() == null ? props.defaultLocale() : r.locale();

    String status = "QUEUED";
    String error = null;
    String body = null;
    Integer version = null;
    String sender = props.defaultSender();
    Integer ref = null;
    Integer segments = null;
    if (!r.channels().contains(Channel.SMS) || msisdn == null || msisdn.isBlank()) {
      status = "FAILED";
      error = ErrorCodes.NOTIFY_NO_CHANNEL;
    } else if (!expiresAt.isAfter(now)) {
      status = "EXPIRED";
    } else {
      try {
        Optional<TemplateRecord> t = templates.findActive(r.templateCode(), Channel.SMS.name(), locale, now);
        TemplateRecord template = t.orElseThrow(() -> new TemplateException("no active template"));
        body = renderer.render(template.body(), r.params());
        version = template.version();
        if (template.senderId() != null) {
          sender = template.senderId();
        }
        ref = ThreadLocalRandom.current().nextInt(256);
        segments = SmsEncoder.segmentCount(body);
      } catch (TemplateException e) {
        status = "FAILED";
        error = e.code();
        log.atWarn()
            .addKeyValue("event", "notify.template.failed")
            .addKeyValue("messageId", r.messageId())
            .addKeyValue("templateCode", r.templateCode())
            .addKeyValue("locale", locale)
            .log(e.getMessage());
      }
    }
    Instant next = "QUEUED".equals(status) ? latest(now, r.sendAt()) : null;
    MessageRecord m = new MessageRecord(r.messageId(), r.txnId(), r.correlationId(), Channel.SMS.name(), msisdn,
        r.templateCode(), version, locale, priority.name(), sender, body, ref, segments, status, 0, next,
        expiresAt, error);
    if (!messages.insertIfAbsent(m, priority.rank())) {
      log.atInfo()
          .addKeyValue("event", "notify.duplicate")
          .addKeyValue("messageId", r.messageId())
          .addKeyValue("txnId", r.txnId())
          .log("duplicate messageId dropped");
      return false;
    }
    switch (status) {
      case "QUEUED" -> {
        messages.insertSegments(m.messageId(), segments);
        log.atInfo()
            .addKeyValue("event", "notify.queued")
            .addKeyValue("messageId", m.messageId())
            .addKeyValue("txnId", m.txnId())
            .addKeyValue("priority", priority)
            .addKeyValue("recipient", MaskingPolicy.mask(SensitiveType.MSISDN, msisdn))
            .addKeyValue("segments", segments)
            .log("notification queued");
      }
      case "EXPIRED" -> statuses.publish(m, NotifyStatus.EXPIRED, true, 1, null, null, now);
      default -> statuses.publish(m, NotifyStatus.FAILED, true, 1, null, error, now);
    }
    return true;
  }

  private static Instant latest(Instant now, Instant sendAt) {
    return sendAt != null && sendAt.isAfter(now) ? sendAt : now;
  }
}
