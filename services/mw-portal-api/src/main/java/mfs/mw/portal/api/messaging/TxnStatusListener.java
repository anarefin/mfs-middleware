package mfs.mw.portal.api.messaging;

import mfs.mw.portal.api.dto.StatusMessage;
import mfs.mw.portal.api.service.BulkExecutionService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind {@code route/TxnStatusRoute}: hands every status record to {@link
 * BulkExecutionService#apply}, which keeps only the org portal's bulk children. Applying is a
 * conditional update, so a redelivered record is a no-op and needs no inbox row. A record that
 * cannot be read throws, so the route parks it on a retry topic and finally the DLQ. Short database
 * work only, never a wait, on the consumer thread.
 */
@Component("txnStatusListener")
public class TxnStatusListener {

  private final BulkExecutionService execution;
  private final JsonMapper json;

  public TxnStatusListener(BulkExecutionService execution, JsonMapper json) {
    this.execution = execution;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    execution.apply(json.readValue(exchange.getMessage().getBody(String.class), StatusMessage.class));
  }
}
