package mfs.mw.orchestrator.route;

import java.time.Clock;
import mfs.mw.orchestrator.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes {@code mw.partner.reply.v1} with retry topics and DLQ (spec 6.2, 19.2). */
@Component
@ConditionalOnProperty(name = "mw.orchestrator.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class PartnerReplyRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.partner.reply.v1";

  public PartnerReplyRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:partnerReplyListener?method=handle";
  }
}
