package mfs.mw.portal.api.route;

import java.time.Clock;
import mfs.mw.portal.api.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code mw.txn.status.v1} (group {@code mw-portal-api}) to settle the rows of bulk batches
 * from their children's status records, with retry topics and DLQ (spec 6.2, 19.2; ticket 34).
 */
@Component
@ConditionalOnProperty(name = "mw.portal-api.consumers.enabled", havingValue = "true", matchIfMissing = true)
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
