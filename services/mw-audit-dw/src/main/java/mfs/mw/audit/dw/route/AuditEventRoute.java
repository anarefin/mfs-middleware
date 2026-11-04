package mfs.mw.audit.dw.route;

import java.time.Clock;
import mfs.mw.audit.dw.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes {@code mw.audit.event.v1} with retry topics and DLQ (spec 6.2, 14.3, 19.2). */
@Component
@ConditionalOnProperty(name = "mw.audit-dw.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class AuditEventRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.audit.event.v1";

  public AuditEventRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:auditEventListener?method=handle";
  }
}
