package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.util.UUID;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.OptOutMessage;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.messaging.EventMetadata;
import mfs.mw.app.gateway.messaging.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Promotional opt-out from the app (ticket 111, NOT-09). Publishes {@code mw.notify.opt-out.v1}
 * (key = the wallet) for mw-notify, which stores the MSISDN hash and skips PROMO only. Nothing is
 * stored here. Kafka refusing or timing out is 503 {@code MW-SYS-010}, retryable. The MSISDN comes
 * from the token and is never logged in clear.
 */
@Service
public class OptOutService {

  public static final String TOPIC = "mw.notify.opt-out.v1";

  private static final Logger log = LoggerFactory.getLogger(OptOutService.class);

  private final EventPublisher publisher;
  private final Clock clock;

  public OptOutService(EventPublisher publisher, Clock clock) {
    this.publisher = publisher;
    this.clock = clock;
  }

  /** The token's wallet opts out of promotional messages. {@code deviceId} is the session's bound device. */
  public void optOut(AppUser user, String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "X-Device-Id is required").withStatus(HttpStatus.BAD_REQUEST);
    }
    String correlationId = TxnReplyExchange.correlationId();
    OptOutMessage msg = new OptOutMessage(1, user.msisdn(), "APP", clock.instant(), correlationId);
    String id = "opt-out:" + UUID.randomUUID();
    try {
      publisher.publish(TOPIC, user.msisdn(), new EventMetadata(correlationId, id, id, 1), msg);
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    log.atInfo().addKeyValue("event", "notify.optout.published").addKeyValue("channel", user.channel())
        .log("promotional opt-out published");
  }
}
