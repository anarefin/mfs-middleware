package mfs.mw.auth.route;

import java.time.Clock;
import mfs.mw.auth.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code auth.wallet-lock.v1} in group {@code mw-auth} (ticket 70, spec 18.7 Revocation):
 * LOCK, UNLOCK, REVOKE_SESSIONS from mw-admin-api (later mw-compliance, mw-care-api). Failures go to
 * {@code auth.wallet-lock.v1.mw-auth.retry.1..3}, then the DLQ ({@code deploy/kafka/topics.txt}).
 */
@Component
@ConditionalOnProperty(name = "mw.auth.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class WalletLockRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "auth.wallet-lock.v1";

  public WalletLockRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:walletLockListener?method=handle";
  }
}
