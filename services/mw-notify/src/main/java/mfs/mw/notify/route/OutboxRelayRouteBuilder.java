package mfs.mw.notify.route;

import mfs.mw.notify.config.OutboxProperties;
import mfs.mw.notify.messaging.OutboxRelay;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * The Camel route that relays the outbox (spec 6.4): every {@code mw.outbox.relay.period} it
 * publishes all unsent rows. A timer thread, not a Kafka consumer thread. Off when {@code
 * mw.outbox.relay.enabled=false}.
 */
@Component
public class OutboxRelayRouteBuilder extends RouteBuilder {

  private final OutboxRelay relay;
  private final OutboxProperties properties;

  public OutboxRelayRouteBuilder(OutboxRelay relay, OutboxProperties properties) {
    this.relay = relay;
    this.properties = properties;
  }

  @Override
  public void configure() {
    from("timer:outbox-relay?delay=0&period=" + properties.period().toMillis())
        .routeId("outbox.relay")
        .autoStartup(properties.enabled())
        .process(e -> relay.relayPending());
  }
}
