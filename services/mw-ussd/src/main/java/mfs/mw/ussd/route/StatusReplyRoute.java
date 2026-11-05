package mfs.mw.ussd.route;

import mfs.mw.ussd.config.UssdProperties;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reply consumer of {@code mw.txn.status.v1}, one consumer group per instance ({@code
 * mw-ussd.reply.<instanceId>}, spec 7.5): every instance sees every status record and keeps
 * those addressed to it. Starts at the latest offset and auto-commits: a reply is only useful to
 * a request waiting right now, so nothing is replayed, retried or parked.
 */
@Component
@ConditionalOnProperty(name = "mw.ussd.consumers.enabled", havingValue = "true", matchIfMissing = true)
public class StatusReplyRoute extends RouteBuilder {

  private final UssdProperties props;

  public StatusReplyRoute(UssdProperties props) {
    this.props = props;
  }

  @Override
  public void configure() {
    from("kafka:" + props.statusTopic()
            + "?groupId={{camel.component.kafka.group-id}}.reply." + props.instanceId()
            + "&autoOffsetReset=latest&autoCommitEnable=true&allowManualCommit=false"
            + "&breakOnFirstError=false")
        .routeId(props.statusTopic() + ".reply")
        .to("bean:statusReplyListener?method=handle");
  }
}
