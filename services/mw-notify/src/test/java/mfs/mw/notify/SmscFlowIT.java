package mfs.mw.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import mfs.mw.notify.client.SmscStub;
import mfs.mw.notify.messaging.EventMetadata;
import mfs.mw.notify.messaging.EventPublisher;
import mfs.mw.notify.messaging.KafkaTestConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service against an SMSC over SMPP (spec 10, ticket 24): Kafka in ({@code
 * mw.txn.status.v1} rules and {@code mw.notify.request.v1}), real SMPP binds to an in-test SMSC
 * ({@link SmscStub}), DLRs back, {@code mw.notify.status.v1} out through the outbox relay. The
 * retry back-off is shortened here; its 2/10/30 s schedule is pinned by NotificationFlowIT.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=true",
      "mw.notify.smpp.enabled=true",
      "mw.notify.dispatch.enabled=true",
      "mw.notify.dispatch.period=100ms",
      "mw.notify.retry-backoff=200ms,200ms,200ms",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=200ms",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class SmscFlowIT {

  static final SmscStub SMSC;

  static {
    try {
      SMSC = new SmscStub();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void smsc(DynamicPropertyRegistry registry) {
    registry.add("mw.notify.smpp.host", () -> "127.0.0.1");
    registry.add("mw.notify.smpp.port", SMSC::port);
  }

  @AfterAll
  static void stop() throws IOException {
    SMSC.close();
  }

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JsonMapper json;

  @Autowired
  SmscFlowIT(EventPublisher publisher, KafkaContainer kafka, JsonMapper json) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.json = json;
  }

  List<JsonNode> finalStatuses(String messageId, int count) {
    return KafkaTestConsumer.read(kafka.getBootstrapServers(), "mw.notify.status.v1", count, Duration.ofSeconds(60),
            r -> messageId.equals(r.key()) && json.readTree(r.value()).get("final").asBoolean())
        .stream()
        .map(ConsumerRecord::value)
        .map(json::readTree)
        .toList();
  }

  static <T> T await(java.util.function.Supplier<T> value, java.util.function.Predicate<T> done) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    T v = value.get();
    while (!done.test(v) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      v = value.get();
    }
    return v;
  }

  @Test
  void aCompletedP2pSendsBothSmsOverSmppAndEachEndsDelivered() {
    String txn = UUID.randomUUID().toString();
    String sender = "01711" + (100000 + (int) (Math.random() * 899999));
    String receiver = "01811" + (100000 + (int) (Math.random() * 899999));
    String status =
        """
        {"schemaVersion":1,"txnId":"%s","correlationId":"corr-%s","channel":"CUSTOMER_APP","txnType":"P2P",
         "status":"COMPLETED","previousStatus":"POSTING",
         "initiator":{"type":"MSISDN","id":"%s"},
         "debitParty":{"type":"WALLET","id":"%s","walletType":"CUSTOMER"},
         "creditParty":{"type":"WALLET","id":"%s","walletType":"CUSTOMER"},
         "amount":{"valueMinor":50000,"currency":"BDT"},"fee":{"valueMinor":0,"currency":"BDT"},
         "requestedAt":"2026-11-03T08:15:30Z","updatedAt":"2026-11-03T08:15:31Z","locale":"en"}
        """
            .formatted(txn, txn, sender, sender, receiver);
    publisher.publish("mw.txn.status.v1", txn, new EventMetadata("corr-" + txn, txn, txn + ":COMPLETED", 1),
        json.readTree(status));

    List<SmscStub.Received> toSender = await(() -> SMSC.receivedFor(sender), l -> !l.isEmpty());
    List<SmscStub.Received> toReceiver = await(() -> SMSC.receivedFor(receiver), l -> !l.isEmpty());

    assertThat(toSender).singleElement().satisfies(r -> {
      assertThat(r.dataCoding()).isEqualTo((byte) 0);
      assertThat(SmscStub.text(r)).startsWith("You have sent Tk 500.00 to " + receiver.substring(0, 4) + "****"
          + receiver.substring(8));
      assertThat(r.source()).isEqualTo("upay");
    });
    assertThat(SmscStub.text(toReceiver.getFirst()))
        .startsWith("You have received Tk 500.00 from " + sender.substring(0, 4) + "****");

    assertThat(finalStatuses(txn + ":sms:p2p-sent", 1))
        .singleElement()
        .satisfies(s -> assertThat(s.get("status").asString()).isEqualTo("DELIVERED"));
    assertThat(finalStatuses(txn + ":sms:p2p-received", 1))
        .singleElement()
        .satisfies(s -> assertThat(s.get("status").asString()).isEqualTo("DELIVERED"));
  }

  @Test
  void aThrottledSubmitIsRetriedUntilTheSmscTakesItAndTheBanglaSmsGoesAsUcs2Parts() {
    String id = "req-" + UUID.randomUUID();
    String msisdn = "01911" + (100000 + (int) (Math.random() * 899999));
    SMSC.throttle(msisdn, 2);
    String request =
        """
        {"schemaVersion":1,"messageId":"%s","recipient":{"msisdn":"%s"},"templateCode":"TXN_P2P_SENT",
         "locale":"bn","params":{"amount":"500.00","counterparty":"01811000002","fee":"0.00",
         "txnId":"01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b"},"channels":["SMS"],"priority":"TXN"}
        """
            .formatted(id, msisdn);
    publisher.publish("mw.notify.request.v1", msisdn, new EventMetadata("corr-" + id, id, id, 1),
        json.readTree(request));

    List<JsonNode> done = finalStatuses(id, 1);

    assertThat(done).singleElement().satisfies(s -> {
      assertThat(s.get("status").asString()).isEqualTo("DELIVERED");
      assertThat(s.get("attempt").asInt()).isEqualTo(3);
    });
    List<SmscStub.Received> parts = SMSC.receivedFor(msisdn);
    assertThat(parts).hasSizeGreaterThan(1).allSatisfy(p -> {
      assertThat(p.dataCoding()).isEqualTo((byte) 0x08);
      assertThat(p.esmClass() & 0x40).isEqualTo(0x40);
    });
  }
}
