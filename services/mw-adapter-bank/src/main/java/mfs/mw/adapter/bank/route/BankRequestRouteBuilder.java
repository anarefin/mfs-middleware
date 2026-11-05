package mfs.mw.adapter.bank.route;

import java.time.Clock;
import mfs.mw.adapter.bank.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code mw.partner.request.bank.v1} (key txnId) with retry topics and DLQ (spec 19.2).
 * On by default; {@code mw.bank.request-consumer.enabled=false} switches it off (tests, so a
 * cached test context never competes for the topic with the context under test).
 */
@Component
@ConditionalOnProperty(name = "mw.bank.request-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class BankRequestRouteBuilder extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.partner.request.bank.v1";

  public BankRequestRouteBuilder(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:bankRequestHandler?method=handle";
  }
}
