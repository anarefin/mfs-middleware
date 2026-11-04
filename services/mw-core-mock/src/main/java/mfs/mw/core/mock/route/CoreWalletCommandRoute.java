package mfs.mw.core.mock.route;

import java.time.Clock;
import mfs.mw.core.mock.config.KafkaRetryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes {@code core.wallet.command.v1} (spec 6.3) with retry topics and DLQ (19.2). */
@Component
@ConditionalOnProperty(name = "mw.coremock.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class CoreWalletCommandRoute extends RetryTopicRouteBuilder {

  public static final String TOPIC = "core.wallet.command.v1";

  private final int consumers;

  /**
   * {@code consumers} = {@code mw.coremock.consumer.count}: consumer threads on the topic, each on
   * its share of the partitions (commands are keyed by txnId, so per-txn order holds; wallets are
   * locked in id order, so threads cannot deadlock).
   */
  public CoreWalletCommandRoute(
      KafkaRetryProperties retry, Clock clock, @Value("${mw.coremock.consumer.count:4}") int consumers) {
    super(retry, clock);
    this.consumers = consumers;
  }

  @Override
  protected String mainConsumerOptions() {
    return "?consumersCount=" + consumers;
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
