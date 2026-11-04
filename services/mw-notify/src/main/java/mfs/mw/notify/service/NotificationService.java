package mfs.mw.notify.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * Accepts a {@link NotificationRequest} (spec 10.1): one {@code notify.message} row per requested
 * channel, keyed (messageId, channel), so a repeated {@code messageId} is dropped. A message
 * already past {@code expiresAt} (default by priority) is recorded EXPIRED; otherwise the channel's
 * template is rendered with masked params and the message is queued for the dispatcher. SMS needs
 * an MSISDN, EMAIL an address (subject, text part and optional HTML part); a channel without its
 * address, PUSH (not built) or a template that cannot be rendered is recorded FAILED. Nothing is
 * sent here, so a Kafka listener thread never waits on the SMSC or the mail server.
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
    String locale = r.locale() == null ? props.defaultLocale() : r.locale();
    // A request naming no channel is recorded as a failed SMS, as before email existed.
    List<Channel> channels = r.channels().isEmpty() ? List.of(Channel.SMS) : r.channels();

    boolean inserted = false;
    for (Channel channel : channels) {
      Prepared p = prepare(r, channel, locale, now, expiresAt);
      Instant next = "QUEUED".equals(p.status) ? latest(now, r.sendAt()) : null;
      MessageRecord m = new MessageRecord(r.messageId(), r.txnId(), r.correlationId(), channel.name(), p.msisdn,
          r.templateCode(), p.version, locale, priority.name(), p.sender, p.body, p.ref, p.segments, p.status, 0,
          next, expiresAt, p.error, p.email, p.subject, p.html);
      if (!messages.insertIfAbsent(m, priority.rank())) {
        continue;
      }
      inserted = true;
      switch (p.status) {
        case "QUEUED" -> queued(m, priority);
        case "EXPIRED" -> statuses.publish(m, NotifyStatus.EXPIRED, true, 1, null, null, now);
        default -> statuses.publish(m, NotifyStatus.FAILED, true, 1, null, p.error, now);
      }
    }
    if (!inserted) {
      log.atInfo()
          .addKeyValue("event", "notify.duplicate")
          .addKeyValue("messageId", r.messageId())
          .addKeyValue("txnId", r.txnId())
          .log("duplicate messageId dropped");
    }
    return inserted;
  }

  private void queued(MessageRecord m, Priority priority) {
    if (Channel.SMS.name().equals(m.channel())) {
      messages.insertSegments(m.messageId(), m.segmentCount());
    }
    log.atInfo()
        .addKeyValue("event", "notify.queued")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("channel", m.channel())
        .addKeyValue("priority", priority)
        .addKeyValue("recipient", m.recipientMsisdn() == null ? null
            : MaskingPolicy.mask(SensitiveType.MSISDN, m.recipientMsisdn()))
        .addKeyValue("segments", m.segmentCount())
        .log("notification queued");
  }

  /** What one channel of the request becomes: rendered and QUEUED, EXPIRED or FAILED. */
  private Prepared prepare(NotificationRequest r, Channel channel, String locale, Instant now, Instant expiresAt) {
    Prepared p = new Prepared();
    NotificationRequest.Recipient to = r.recipient();
    p.msisdn = to == null || channel != Channel.SMS ? null : blankToNull(to.msisdn());
    p.email = to == null || channel != Channel.EMAIL ? null : blankToNull(to.email());
    p.sender = channel == Channel.SMS ? props.defaultSender() : null;
    boolean addressed = switch (channel) {
      case SMS -> p.msisdn != null;
      case EMAIL -> p.email != null;
      case PUSH -> false;
    };
    if (!addressed) {
      p.status = "FAILED";
      p.error = ErrorCodes.NOTIFY_NO_CHANNEL;
      return p;
    }
    if (!expiresAt.isAfter(now)) {
      p.status = "EXPIRED";
      return p;
    }
    try {
      TemplateRecord template = templates.findActive(r.templateCode(), channel.name(), locale, now)
          .orElseThrow(() -> new TemplateException("no active template"));
      p.version = template.version();
      p.body = renderer.render(template.body(), r.params());
      if (channel == Channel.SMS) {
        if (template.senderId() != null) {
          p.sender = template.senderId();
        }
        p.ref = ThreadLocalRandom.current().nextInt(256);
        p.segments = SmsEncoder.segmentCount(p.body);
      } else {
        if (template.subject() == null || template.subject().isBlank()) {
          throw new TemplateException("email template without subject");
        }
        p.subject = renderer.render(template.subject(), r.params());
        p.html = template.htmlBody() == null ? null : renderer.renderHtml(template.htmlBody(), r.params());
      }
      p.status = "QUEUED";
    } catch (TemplateException e) {
      p.status = "FAILED";
      p.error = e.code();
      p.body = null;
      p.subject = null;
      p.html = null;
      log.atWarn()
          .addKeyValue("event", "notify.template.failed")
          .addKeyValue("messageId", r.messageId())
          .addKeyValue("channel", channel)
          .addKeyValue("templateCode", r.templateCode())
          .addKeyValue("locale", locale)
          .log(e.getMessage());
    }
    return p;
  }

  /** Mutable scratch for {@link #prepare}. */
  private static final class Prepared {
    String status;
    String error;
    String msisdn;
    String email;
    String sender;
    String body;
    String subject;
    String html;
    Integer version;
    Integer ref;
    Integer segments;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static Instant latest(Instant now, Instant sendAt) {
    return sendAt != null && sendAt.isAfter(now) ? sendAt : now;
  }
}
