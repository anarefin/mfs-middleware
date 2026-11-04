package mfs.mw.core.mock.route;

import java.time.Clock;
import mfs.mw.core.mock.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code core.payment.command.v1} (spec 6.3, the core's Digital Payment side) with retry
 * topics and DLQ (19.2). Same handler as the wallet topic; replies go to {@code core.payment.event.v1}.
 */
@Component
@ConditionalOnProperty(name = "mw.coremock.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class CorePaymentCommandRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "core.payment.command.v1";

  public CorePaymentCommandRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:coreCommandListener?method=handlePayment";
  }
}
