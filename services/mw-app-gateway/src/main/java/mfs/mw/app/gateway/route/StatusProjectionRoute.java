package mfs.mw.app.gateway.route;

import java.time.Clock;
import mfs.mw.app.gateway.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code mw.txn.status.v1} once per service (group {@code mw-app-gateway}) to keep the
 * app's transaction view, with retry topics and DLQ (spec 6.2, 19.2).
 */
@Component
@ConditionalOnProperty(name = "mw.app-gateway.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class StatusProjectionRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.txn.status.v1";

  public StatusProjectionRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:statusProjectionListener?method=handle";
  }
}
