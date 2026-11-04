package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.service.PartnerReplyService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.partner.reply.v1} route. A record that cannot be read or lacks its
 * ids throws, so the route parks it on a retry topic and finally the DLQ. The body is never logged.
 */
@Component("partnerReplyListener")
public class PartnerReplyListener {

  private final PartnerReplyService service;
  private final JsonMapper json;

  public PartnerReplyListener(PartnerReplyService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    PartnerReply reply = json.readValue(exchange.getMessage().getBody(String.class), PartnerReply.class);
    if (reply.requestId() == null || reply.requestId().isBlank() || reply.txnId() == null || reply.payload() == null) {
      throw new IllegalArgumentException("partner reply lacks requestId, txnId or payload");
    }
    service.handle(reply);
  }
}
