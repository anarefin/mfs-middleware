package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.PartnerInbound;
import mfs.mw.orchestrator.service.PartnerInboundService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.partner.inbound.v1} route (ticket 22). A record that cannot be
 * read or lacks its ids throws, so the route parks it on a retry topic and finally the DLQ. The
 * body is never logged.
 */
@Component("partnerInboundListener")
public class PartnerInboundListener {

  private final PartnerInboundService service;
  private final JsonMapper json;

  public PartnerInboundListener(PartnerInboundService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    PartnerInbound inbound = json.readValue(exchange.getMessage().getBody(String.class), PartnerInbound.class);
    if (inbound.inboundId() == null || inbound.inboundId().isBlank() || inbound.txnId() == null
        || inbound.payload() == null) {
      throw new IllegalArgumentException("partner inbound message lacks inboundId, txnId or payload");
    }
    service.handle(inbound);
  }
}
