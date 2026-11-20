package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.util.UUID;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.PushTokenMessage;
import mfs.mw.app.gateway.dto.PushTokenRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.messaging.EventMetadata;
import mfs.mw.app.gateway.messaging.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Registers the app's push device token (ticket 110, FRS EXT-04): publishes {@code mw.notify.push-token.v1}
 * (key = the wallet) for mw-notify, which keeps it and pushes transaction notices to it. Nothing is stored
 * here, so the record goes straight to Kafka (no outbox: no local change to commit with it). Kafka refusing
 * or timing out is 503 {@code MW-SYS-010}, retryable. The token is never logged.
 */
@Service
public class PushTokenService {

  public static final String TOPIC = "mw.notify.push-token.v1";

  private static final Logger log = LoggerFactory.getLogger(PushTokenService.class);

  private final EventPublisher publisher;
  private final Clock clock;

  public PushTokenService(EventPublisher publisher, Clock clock) {
    this.publisher = publisher;
    this.clock = clock;
  }

  /** The token of {@code deviceId} (the request's bound device, checked by the device gate) for the user's wallet. */
  public void register(AppUser user, String deviceId, PushTokenRequest body) {
    String correlationId = TxnReplyExchange.correlationId();
    PushTokenMessage msg = new PushTokenMessage(1, user.msisdn(), deviceId, body.token(), body.platform(),
        clock.instant(), correlationId);
    String id = "push-token:" + UUID.randomUUID();
    try {
      publisher.publish(TOPIC, user.msisdn(), new EventMetadata(correlationId, id, id, 1), msg);
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    log.atInfo()
        .addKeyValue("event", "push.token.published")
        .addKeyValue("channel", user.channel())
        .addKeyValue("platform", body.platform())
        .log("push token published");
  }
}
