package mfs.mw.ussd.messaging;

import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.StatusMessage;
import mfs.mw.ussd.service.ReplyWaiters;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-instance reply consumer of {@code mw.txn.status.v1} (spec 7.5): keeps only records whose
 * {@code mw-reply-to} is this instance and hands them to the waiting request. Never blocks and
 * never throws: a reply that cannot be read is dropped (the request then answers 202 and the app
 * polls), it is not retried.
 */
@Component("statusReplyListener")
public class StatusReplyListener {

  private static final Logger log = LoggerFactory.getLogger(StatusReplyListener.class);

  private final ReplyWaiters waiters;
  private final JsonMapper json;
  private final String instanceId;

  public StatusReplyListener(ReplyWaiters waiters, JsonMapper json, UssdProperties props) {
    this.waiters = waiters;
    this.json = json;
    this.instanceId = props.instanceId();
  }

  public void handle(Exchange exchange) {
    if (!instanceId.equals(MwHeaders.text(exchange.getMessage().getHeader(MwHeaders.REPLY_TO)))) {
      return;
    }
    try {
      StatusMessage status =
          json.readValue(exchange.getMessage().getBody(String.class), StatusMessage.class);
      if (waiters.offer(status)) {
        log.atDebug()
            .addKeyValue("event", "txn.reply.matched")
            .addKeyValue("txnId", status.awaitedTxnId())
            .log("answer handed to the waiting request");
      }
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "txn.reply.unreadable")
          .addKeyValue("errorClass", e.getClass().getName())
          .log("status record addressed to this instance could not be read");
    }
  }
}
