package mfs.mw.adapter.interop.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import mfs.mw.adapter.interop.config.MwServiceProperties;
import mfs.mw.adapter.interop.dto.PartnerRouteStatusEvent;
import mfs.mw.adapter.interop.partner.config.PartnerProperties;
import mfs.mw.adapter.interop.partner.route.PartnerRouteStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The partner framework's route status sink (ticket 83): every LIVE / REJECTED outcome of a record
 * version becomes a {@code mw.partner.route.status.v1} record (key partnerId) through the outbox,
 * in a transaction of its own. The 6.1 txn header is a name UUID of {@code partner/<id>@<version>}
 * (a config change has no transaction), the idempotency key {@code partner/<id>@<version>:<status>};
 * a health report (ticket 97) {@code partner/<id>@<version>:health:<health>:<epoch ms>}, each change
 * its own record.
 */
@Component
public class PartnerRouteStatusPublisher implements PartnerRouteStatus.Sink {

  public static final String TOPIC = "mw.partner.route.status.v1";

  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final MwServiceProperties service;
  private final PartnerProperties partners;
  private final Clock clock;

  public PartnerRouteStatusPublisher(
      OutboxWriter outbox, PlatformTransactionManager transactions, MwServiceProperties service,
      PartnerProperties partners, Clock clock) {
    this.outbox = outbox;
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.service = service;
    this.partners = partners;
    this.clock = clock;
  }

  @Override
  public void publish(PartnerRouteStatus status) {
    PartnerRouteStatusEvent event = toEvent(status);
    String version = "partner/" + status.partnerId() + "@" + status.version();
    String id = UUID.nameUUIDFromBytes(version.getBytes(StandardCharsets.UTF_8)).toString();
    tx.executeWithoutResult(
        s -> outbox.append(TOPIC, status.partnerId(), new EventMetadata(id, id, key(version, status), 1), event));
  }

  private String key(String version, PartnerRouteStatus status) {
    return status.health() == null
        ? version + ":" + status.status()
        : version + ":health:" + status.health() + ":" + clock.millis();
  }

  PartnerRouteStatusEvent toEvent(PartnerRouteStatus s) {
    return new PartnerRouteStatusEvent(
        1, s.partnerId(), s.version(), partners.family(), s.status().name(), s.problems(), s.kamelets(), s.health(), service.name(),
        clock.instant());
  }
}
