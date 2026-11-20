package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.util.UUID;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.LiveSession;
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
 *
 * <p>Binding epoch (ticket 110b): the record names mw-auth's epoch of the device's current binding to the
 * wallet, read from a fresh live session lookup (not the device gate's 10 s confirmation cache), so mw-notify
 * can refuse a registration of a binding a newer one replaced without comparing the services' clocks. No live
 * session (the device lost its binding meanwhile) is 401 {@code MW-AUTH-007}; mw-auth unreachable or an answer
 * without the epoch 503 {@code MW-SYS-012}; nothing is published then.
 */
@Service
public class PushTokenService {

  public static final String TOPIC = "mw.notify.push-token.v1";

  private static final Logger log = LoggerFactory.getLogger(PushTokenService.class);

  private final EventPublisher publisher;
  private final AuthClient auth;
  private final Clock clock;

  public PushTokenService(EventPublisher publisher, AuthClient auth, Clock clock) {
    this.publisher = publisher;
    this.auth = auth;
    this.clock = clock;
  }

  /** The token of {@code deviceId} (the request's bound device, checked by the device gate) for the user's wallet. */
  public void register(AppUser user, String deviceId, PushTokenRequest body) {
    long epoch = bindingEpoch(user, deviceId);
    String correlationId = TxnReplyExchange.correlationId();
    PushTokenMessage msg = new PushTokenMessage(1, user.msisdn(), deviceId, body.token(), body.platform(),
        clock.instant(), epoch, correlationId);
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

  /** mw-auth's epoch of the device's current binding to the user's wallet, from a fresh live session lookup. */
  private long bindingEpoch(AppUser user, String deviceId) {
    LiveSession live = auth.session(user.msisdn(), deviceId)
        .orElseThrow(() -> new MwException(ErrorCodes.AUTH_NO_SESSION, "Session expired, sign in again"));
    if (live.bindingEpoch() == null || live.bindingEpoch() < 1) {
      throw new MwException(ErrorCodes.SYS_SESSION_STORE_UNAVAILABLE, "Service temporarily unavailable, try again")
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return live.bindingEpoch();
  }
}
