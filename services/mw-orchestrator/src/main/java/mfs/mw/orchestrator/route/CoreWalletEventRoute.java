package mfs.mw.orchestrator.route;

import java.time.Clock;
import mfs.mw.orchestrator.config.KafkaRetryProperties;
import mfs.mw.orchestrator.service.CoreTopics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes {@code core.wallet.event.v1} with retry topics and DLQ (spec 6.2, 19.2). */
@Component
@ConditionalOnProperty(name = "mw.orchestrator.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class CoreWalletEventRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = CoreTopics.WALLET_EVENTS;

  public CoreWalletEventRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:coreEventListener?method=handle";
  }
}
