package mfs.mw.auth.route;

import java.time.Clock;
import mfs.mw.auth.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code core.wallet.event.v1} in group {@code mw-auth} (ticket 67, D5): the core's
 * {@code WalletStateChanged} feeds the wallet projection; the topic's other events (replies to
 * mw-orchestrator) are skipped. Failures go to {@code core.wallet.event.v1.mw-auth.retry.1..3},
 * then the DLQ ({@code deploy/kafka/topics.txt} lists the group).
 */
@Component
@ConditionalOnProperty(name = "mw.auth.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class CoreWalletEventRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "core.wallet.event.v1";

  public CoreWalletEventRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:coreWalletEventListener?method=handle";
  }
}
