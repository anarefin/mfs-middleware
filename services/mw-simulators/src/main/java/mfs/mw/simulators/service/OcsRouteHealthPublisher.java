package mfs.mw.simulators.service;

import java.time.Clock;
import java.util.UUID;
import mfs.mw.simulators.config.MwServiceProperties;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.messaging.EventMetadata;
import mfs.mw.simulators.messaging.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Publishes one OCS switch change as partner health (ticket 112c). A publish failure is logged. */
@Component
public class OcsRouteHealthPublisher {

  static final String TOPIC = "mw.partner.route.status.v1";

  private static final Logger log = LoggerFactory.getLogger(OcsRouteHealthPublisher.class);

  private final EventPublisher publisher;
  private final Clock clock;
  private final String reportedBy;

  public OcsRouteHealthPublisher(EventPublisher publisher, Clock clock, MwServiceProperties service) {
    this.publisher = publisher;
    this.clock = clock;
    this.reportedBy = service.name();
  }

  public void publish(String party, MockSwitch.State state) {
    String partnerId = OcsRouteHealth.partnerId(party);
    if (partnerId == null || state == null) {
      return;
    }
    String health = OcsRouteHealth.health(state);
    String id = UUID.randomUUID().toString();
    try {
      publisher.publish(
          TOPIC,
          partnerId,
          new EventMetadata(id, id, "health:" + partnerId + ":" + health + ":" + clock.millis(), 1),
          OcsRouteHealth.event(partnerId, health, reportedBy, clock.instant()));
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "partner.health.publish_failed")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("errorCode", e.getClass().getSimpleName())
          .log("partner health was not published");
    }
  }
}
