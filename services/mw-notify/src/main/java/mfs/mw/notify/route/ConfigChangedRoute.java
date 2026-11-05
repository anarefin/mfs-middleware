package mfs.mw.notify.route;

import java.time.Clock;
import mfs.mw.notify.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code mw.config.changed.v1} (compacted; spec 20.4, ticket 39) with retry topics and DLQ
 * (spec 19.2): notify template versions published by the admin console. Group {@code mw-notify},
 * earliest offset, so a service started on an empty group reads the latest record of every key.
 */
@Component
@ConditionalOnProperty(name = "mw.notify.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigChangedRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.config.changed.v1";

  public ConfigChangedRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:configChangedListener?method=handle";
  }
}
