package mfs.mw.adapter.atm.messaging;

import java.util.concurrent.ExecutorService;
import mfs.mw.adapter.atm.dto.PartnerRequest;
import mfs.mw.adapter.atm.service.AtmPartnerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listener of {@code mw.partner.request.atm.v1} (key txnId; Spring Kafka retry topics and DLQ,
 * spec 19.2, ADR D27). On by default; {@code mw.atm.request-consumer.enabled=false} keeps it
 * from starting (tests, so a cached test context never competes for the topic). It only parses and hands the
 * request to a virtual-thread executor: the bank call (up to 25 s) never runs on the Kafka
 * consumer thread. A record that cannot be parsed, or lacks the ids a reply needs, throws, so the
 * error handler parks it on the retry topics and finally the DLQ (spec 19.2).
 *
 * <p>The offset is committed once the request is handed over; the reply is written by the
 * executor task (outbox + inbox, effectively-once). A crash between hand-over and reply leaves the
 * transaction without a reply; the orchestrator's partner deadline then moves it to UNKNOWN and
 * status inquiry resolves it with the stable partner reference (spec 7.4, 15).
 */
@Component
public class AtmRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(AtmRequestHandler.class);

  private final JsonMapper json;
  private final AtmPartnerService service;
  private final ExecutorService executor;

  public AtmRequestHandler(
      JsonMapper json, AtmPartnerService service, @Qualifier("atmCallExecutor") ExecutorService executor) {
    this.json = json;
    this.service = service;
    this.executor = executor;
  }

  public static final String TOPIC = "mw.partner.request.atm.v1";

  @KafkaListener(
      id = "atm-requests",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.atm.request-consumer.enabled:true}")
  public void onRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
    handle(record.value());
    ack.acknowledge();
  }

  /** Parses one record value and hands the request over; throws for a record it cannot use. */
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
