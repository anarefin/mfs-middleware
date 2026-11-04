package mfs.mw.notify.route;

import java.time.Clock;
import mfs.mw.notify.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code mw.txn.status.v1} once for this service (group {@code mw-notify}) to run the
 * notification rules, with retry topics and DLQ (spec 6.2, 10.3, 19.2).
 */
@Component
@ConditionalOnProperty(name = "mw.notify.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class TxnStatusRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.txn.status.v1";

  public TxnStatusRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:txnStatusListener?method=handle";
  }
}
