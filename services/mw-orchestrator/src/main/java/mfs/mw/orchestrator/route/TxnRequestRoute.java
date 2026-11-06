package mfs.mw.orchestrator.route;

import java.time.Clock;
import mfs.mw.orchestrator.config.KafkaRetryProperties;
import mfs.mw.orchestrator.messaging.MwTopics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes {@code mw.txn.request.v1} with retry topics and DLQ (spec 6.2, 19.2). */
@Component
@ConditionalOnProperty(name = "mw.orchestrator.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class TxnRequestRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = MwTopics.TXN_REQUEST;

  public TxnRequestRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:txnRequestListener?method=handle";
  }
}
