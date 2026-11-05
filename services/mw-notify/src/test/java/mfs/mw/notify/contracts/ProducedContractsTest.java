package mfs.mw.notify.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.messaging.EventMetadata;
import mfs.mw.notify.messaging.OutboxWriter;
import mfs.mw.notify.service.StatusEvents;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code produces.events} entry of {@code contracts.yml} (ticket 53): the event is built by
 * the real {@link StatusEvents} (the only writer of {@code mw.notify.status.v1}) from message rows
 * in the states {@code NotificationService}, {@code DispatchService} and {@code ReceiptService}
 * publish, captured at the {@link OutboxWriter}, serialised the way it stores the payload
 * ({@code json.writeValueAsString}) with Spring Boot's default JsonMapper (the module sets no
 * {@code spring.jackson.*} property), and validated against the schema. No Spring, no Docker.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String STATUS = "events/mw-notify/mw.notify.status.v1.schema.json";
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
  static final Instant AT = Instant.parse("2026-11-03T08:15:35Z");

  static MessageRecord message(String messageId, String txnId, String correlationId, String channel, int attempts) {
    return new MessageRecord(messageId, txnId, correlationId, channel, "01711000001", "TXN_P2P_SENT", 1, "bn",
        "TXN", "upay", "body", null, 1, "QUEUED", attempts, null, AT.plusSeconds(3600), null,
        "EMAIL".equals(channel) ? "a@example.test" : null, null, null);
  }

  /** One publish call, as the service code makes it. */
  record Publish(MessageRecord m, NotifyStatus status, boolean isFinal, int attempt, String providerId, String error) {}

  /** Manifest entry -> the publish calls whose events it covers (every status the service sends). */
  static final Map<String, Supplier<List<Publish>>> FIXTURES =
      Map.of(
          STATUS,
          () -> {
            MessageRecord sms = message(TXN + ":sms:P2P_OK", TXN, "corr-1", "SMS", 1);
            MessageRecord otp = message("otp-1", null, null, "SMS", 0);
            MessageRecord email = message(TXN + ":email:BILL_OK", TXN, "corr-2", "EMAIL", 1);
            return List.of(
                // NotificationService: expired on intake / invalid request
                new Publish(otp, NotifyStatus.EXPIRED, true, 1, null, null),
                new Publish(sms, NotifyStatus.FAILED, true, 1, null, "MW-VAL-101"),
                // DispatchService: first part out, all parts out, transient and final failure
                new Publish(sms, NotifyStatus.SENT, false, 1, "smsc-1", null),
                new Publish(sms, NotifyStatus.SENT, true, 1, "smsc-1", null),
                new Publish(otp, NotifyStatus.FAILED, false, 2, null, "MW-SYS-104"),
                new Publish(otp, NotifyStatus.FAILED, true, 4, null, "MW-SYS-102"),
                new Publish(email, NotifyStatus.SENT, true, 1, "<id@mail.test>", null),
                // ReceiptService: DLR outcomes (attempts 0 is clamped to 1 by StatusEvents)
                new Publish(sms, NotifyStatus.DELIVERED, true, 1, "smsc-1", null),
                new Publish(otp, NotifyStatus.UNDELIVERABLE, true, 0, "smsc-2", "MW-SYS-103"),
                new Publish(sms, NotifyStatus.EXPIRED, true, 1, "smsc-1", "MW-SYS-103"));
          });

  /** Runs the publish calls through StatusEvents and returns the payloads as the outbox stores them. */
  static List<JsonNode> published(List<Publish> calls) {
    OutboxWriter outbox = mock(OutboxWriter.class);
    StatusEvents events = new StatusEvents(outbox);
    for (Publish p : calls) {
      events.publish(p.m(), p.status(), p.isFinal(), p.attempt(), p.providerId(), p.error(), AT);
    }
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(outbox, atLeastOnce()).append(eq(StatusEvents.TOPIC), anyString(), any(EventMetadata.class), payload.capture());
    assertThat(payload.getAllValues()).hasSameSizeAs(calls);
    return payload.getAllValues().stream().map(v -> JSON.readTree(JSON.writeValueAsString(v))).toList();
  }

  @TestFactory
  Stream<DynamicTest> everyProducedEventMatchesItsSchema() {
    return ContractManifest.producedEvents().stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry,
                    () -> {
                      assertThat(FIXTURES).as("fixture for manifest entry %s", entry).containsKey(entry);
                      for (JsonNode event : published(FIXTURES.get(entry).get())) {
                        assertThat(ContractSchemas.schema(ContractManifest.schemaPath(entry)).validate(event))
                            .as("%s: %s", entry, event)
                            .isEmpty();
                      }
                    }));
  }

  @Test
  void fixturesAndManifestListTheSameEntries() {
    assertThat(FIXTURES.keySet()).containsExactlyInAnyOrderElementsOf(ContractManifest.producedEvents());
  }
}
