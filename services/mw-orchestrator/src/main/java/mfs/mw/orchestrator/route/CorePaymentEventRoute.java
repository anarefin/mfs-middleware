package mfs.mw.orchestrator.route;

import java.time.Clock;
import mfs.mw.orchestrator.config.KafkaRetryProperties;
import mfs.mw.orchestrator.service.CoreTopics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code core.payment.event.v1} (results of HoldFunds, CommitHold and ReleaseHold, which
 * go on {@code core.payment.command.v1}, ticket 50) with retry topics and DLQ (spec 6.2, 19.2).
 * Same handler as the wallet event topic: the event, not the topic, says what happened.
 */
@Component
@ConditionalOnProperty(name = "mw.orchestrator.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class CorePaymentEventRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = CoreTopics.PAYMENT_EVENTS;

  public CorePaymentEventRoute(KafkaRetryProperties retry, Clock clock) {
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
