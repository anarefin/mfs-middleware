package mfs.mw.notify.route;

import java.time.Clock;
import mfs.mw.notify.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes {@code mw.notify.request.v1} with retry topics and DLQ (spec 6.2, 10.1, 19.2). */
@Component
@ConditionalOnProperty(name = "mw.notify.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class NotifyRequestRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.notify.request.v1";

  public NotifyRequestRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:notifyRequestListener?method=handle";
  }
}
