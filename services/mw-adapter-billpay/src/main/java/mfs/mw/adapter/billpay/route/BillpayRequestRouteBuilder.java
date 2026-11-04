package mfs.mw.adapter.billpay.route;

import java.time.Clock;
import mfs.mw.adapter.billpay.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code mw.partner.request.billpay.v1} (key txnId) with retry topics and DLQ (spec 19.2).
 * On by default; {@code mw.billpay.request-consumer.enabled=false} switches it off (tests, so a
 * cached test context never competes for the topic with the context under test).
 */
@Component
@ConditionalOnProperty(name = "mw.billpay.request-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class BillpayRequestRouteBuilder extends RetryTopicRouteBuilder {

  public static final String TOPIC = "mw.partner.request.billpay.v1";

  public BillpayRequestRouteBuilder(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:billpayRequestHandler?method=handle";
  }
}
