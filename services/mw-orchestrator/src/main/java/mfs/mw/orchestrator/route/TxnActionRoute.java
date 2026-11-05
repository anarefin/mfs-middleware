package mfs.mw.orchestrator.route;

import java.time.Clock;
import mfs.mw.orchestrator.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes operator actions on {@code mw.txn.action.v1} (ticket 37) with retry topics and DLQ (19.2). */
@Component
@ConditionalOnProperty(name = "mw.orchestrator.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class TxnActionRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.txn.action.v1";

  public TxnActionRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:txnActionListener?method=handle";
  }
}
