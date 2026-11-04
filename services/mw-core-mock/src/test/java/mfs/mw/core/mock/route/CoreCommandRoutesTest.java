package mfs.mw.core.mock.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import mfs.mw.core.mock.config.KafkaRetryProperties;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.junit.jupiter.api.Test;

/**
 * The command topics are consumed by several threads (one handles ~40 commands/s; the default bulk
 * rate of 50 children/s needs a validate and a post each), the retry topics by one, as their
 * hold-back logic expects.
 */
class CoreCommandRoutesTest {

  static final KafkaRetryProperties RETRY =
      new KafkaRetryProperties(List.of(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10)));

  static Map<String, String> inputs(RetryTopicRouteBuilder routes) throws Exception {
    routes.setCamelContext(new DefaultCamelContext());
    routes.configure();
    return routes.getRouteCollection().getRoutes().stream()
        .collect(Collectors.toMap(RouteDefinition::getRouteId, r -> r.getInput().getEndpointUri()));
  }

  @Test
  void theWalletCommandTopicHasConcurrentConsumersItsRetryTopicsOne() throws Exception {
    Map<String, String> in = inputs(new CoreWalletCommandRoute(RETRY, Clock.systemUTC(), 4));
    assertThat(in.get("core.wallet.command.v1.consume")).contains("consumersCount=4");
    assertThat(in.get("core.wallet.command.v1.retry.1.consume")).doesNotContain("consumersCount");
  }

  @Test
  void thePaymentCommandTopicHasConcurrentConsumers() throws Exception {
    Map<String, String> in = inputs(new CorePaymentCommandRoute(RETRY, Clock.systemUTC(), 4));
    assertThat(in.get("core.payment.command.v1.consume")).contains("consumersCount=4");
  }
}
