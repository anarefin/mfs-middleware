package mfs.mw.adapter.bank.messaging;

import java.util.concurrent.ExecutorService;
import mfs.mw.adapter.bank.dto.PartnerRequest;
import mfs.mw.adapter.bank.service.BankDebitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.partner.request.bank.v1} route. It only parses and hands the
 * request to a virtual-thread executor: the bank call (up to 25 s) never runs on the Kafka
 * consumer thread. A record that cannot be parsed, or lacks the ids a reply needs, throws, so the
 * route parks it on the retry topics and finally the DLQ (spec 19.2).
 *
 * <p>The offset is committed once the request is handed over; the reply is written by the
 * executor task (outbox + inbox, effectively-once). A crash between hand-over and reply leaves the
 * transaction without a reply; the orchestrator's partner deadline then moves it to UNKNOWN and
 * status inquiry resolves it with the stable partner reference (spec 7.4, 15).
 */
@Component
public class BankRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(BankRequestHandler.class);

  private final JsonMapper json;
  private final BankDebitService service;
  private final ExecutorService executor;

  public BankRequestHandler(
      JsonMapper json, BankDebitService service, @Qualifier("bankCallExecutor") ExecutorService executor) {
    this.json = json;
    this.service = service;
    this.executor = executor;
  }

  /** Called by the route with the record value. */
  public void handle(String body) {
    PartnerRequest request = json.readValue(body, PartnerRequest.class);
    if (isBlank(request.requestId()) || isBlank(request.txnId()) || isBlank(request.correlationId())) {
      throw new IllegalArgumentException("partner request lacks requestId, txnId or correlationId");
    }
    executor.execute(() -> run(request));
  }

  private void run(PartnerRequest request) {
    try {
      service.process(request);
    } catch (RuntimeException e) {
      log.atError()
          .addKeyValue("event", "partner.call.failed")
          .addKeyValue("txnId", request.txnId())
          .addKeyValue("correlationId", request.correlationId())
          .addKeyValue("errorClass", e.getClass().getName())
          .log("bank request could not be completed");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
