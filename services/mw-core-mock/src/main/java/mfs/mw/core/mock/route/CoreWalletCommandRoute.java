package mfs.mw.core.mock.route;

import java.time.Clock;
import mfs.mw.core.mock.config.KafkaRetryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes {@code core.wallet.command.v1} (spec 6.3) with retry topics and DLQ (19.2). */
@Component
@ConditionalOnProperty(name = "mw.coremock.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class CoreWalletCommandRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "core.wallet.command.v1";

  public CoreWalletCommandRoute(KafkaRetryProperties retry, Clock clock) {
    super(retry, clock);
  }

  @Override
  protected String topic() {
    return TOPIC;
  }

  @Override
  protected String handlerUri() {
    return "bean:coreCommandListener?method=handleWallet";
  }
}
