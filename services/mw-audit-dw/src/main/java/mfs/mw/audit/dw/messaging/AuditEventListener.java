package mfs.mw.audit.dw.messaging;

import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.service.AuditService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.audit.event.v1} route: one record, one database transaction. A
 * record that cannot be read or lacks a required field throws, so the route parks it on a retry
 * topic and finally the DLQ. The body is not logged.
 */
@Component("auditEventListener")
public class AuditEventListener {

  private final AuditService service;
  private final JsonMapper json;

  public AuditEventListener(AuditService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    service.record(json.readValue(exchange.getMessage().getBody(String.class), AuditEvent.class));
  }
}
