package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.service.CoreEventService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code core.wallet.event.v1} route. A failure propagates, so the route parks
 * the record on a retry topic. The body is never logged.
 */
@Component("coreEventListener")
public class CoreEventListener {

  private final CoreEventService service;
  private final JsonMapper json;

  public CoreEventListener(CoreEventService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    CoreEvent event = json.readValue(exchange.getMessage().getBody(String.class), CoreEvent.class);
    if (event.eventId() == null || event.eventId().isBlank() || event.txnId() == null) {
      throw new IllegalArgumentException("core event lacks eventId or txnId");
    }
    service.handle(event);
  }
}
