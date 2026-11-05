package mfs.mw.adapter.telco.partner.config;

import java.util.UUID;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code mw.config.changed.v1} (compacted, keyed by configKey) into the {@link
 * PartnerConfigStore}. Every instance must see every record, so each start uses a consumer group
 * of its own and reads the topic from the beginning, committing nothing: the compacted topic
 * replays the latest value of every key, which rebuilds the snapshot (ticket 38: an adapter that
 * starts after a change picks it up). Not a retry-topic route: the topic has no retry or DLQ
 * topics, and the handler never throws. On by default; {@code
 * mw.partners.config-consumer.enabled=false} switches it off (tests).
 */
@Component
@ConditionalOnProperty(name = "mw.partners.config-consumer.enabled", havingValue = "true", matchIfMissing = true)
public class PartnerConfigRouteBuilder extends RouteBuilder {

  public static final String TOPIC = "mw.config.changed.v1";
  public static final String ROUTE_ID = "partner-config";

  private final String groupId;

  public PartnerConfigRouteBuilder(@Value("${spring.application.name}") String application) {
    this.groupId = application + "-config-" + UUID.randomUUID();
  }

  @Override
  public void configure() {
    from("kafka:" + TOPIC
            + "?groupId=" + groupId
            + "&autoOffsetReset=earliest&autoCommitEnable=false&allowManualCommit=false&breakOnFirstError=false")
        .routeId(ROUTE_ID)
        .to("bean:partnerConfigListener?method=onRecord");
  }
}
