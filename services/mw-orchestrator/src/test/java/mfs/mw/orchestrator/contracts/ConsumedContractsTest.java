package mfs.mw.orchestrator.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.PartnerInbound;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.dto.TxnAction;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.mapper.CoreEventMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every event this module consumes, per {@code consumes.events} of {@code contracts.yml}: each
 * {@code valid*.json} sample of the contract ({@code samples/<topic>/[<Msg>/]}) deserialises into
 * the module's own DTO with the JsonMapper the listeners use, and passes the checks the listener
 * itself makes (the ids it requires). Core event samples must also map to a saga event, since the
 * orchestrator lists only the types it acts on.
 *
 * <p>Invalid samples are not asserted: the listeners ({@code TxnRequestListener}, {@code
 * CoreEventListener}, {@code PartnerReplyListener}, {@code PartnerInboundListener}) do no schema or
 * Bean Validation check, only the missing-id checks below, and no invalid sample lacks those ids;
 * the business rules they violate (decimal amount, reversal without original, ...) are judged by the
 * services later, not when the record is read.
 */
class ConsumedContractsTest {

  private static final JsonMapper JSON = ContractManifest.serviceJsonMapper();
  private static final CoreEventMapper CORE_EVENTS = new CoreEventMapper();

  /** Schema path -> how a valid sample is read and checked (sample path, message type or null). */
  private static final Map<String, BiConsumer<Path, String>> READERS = new LinkedHashMap<>();

  static {
    READERS.put("events/mw-orchestrator/mw.txn.request.v1.schema.json", (p, msg) -> {
      TxnRequest r = JSON.readValue(read(p), TxnRequest.class);
      // TxnRequestListener.handle requires these
      assertThat(List.of(r.txnId(), r.idempotencyKey(), r.correlationId(), r.channel(), r.txnType()))
          .as(p.toString()).allSatisfy(v -> assertThat(v).isNotBlank());
      assertThat(r.initiator()).as(p.toString()).isNotNull();
      assertThat(r.initiator().id()).as(p.toString()).isNotNull();
      assertThat(r.amount()).as(p.toString()).isNotNull();
      assertThat(r.requestedAt()).as(p.toString()).isNotNull();
    });
    READERS.put("events/mw-admin-api/mw.txn.action.v1.schema.json", (p, msg) -> {
      TxnAction a = JSON.readValue(read(p), TxnAction.class);
      // TxnActionListener.handle requires these
      assertThat(List.of(a.actionId(), a.txnId(), a.action(), a.checkerId()))
          .as(p.toString()).allSatisfy(v -> assertThat(v).isNotBlank());
    });
    READERS.put("events/mw-core-mock/core.event.v1.schema.json", (p, msg) -> {
      CoreEvent e = JSON.readValue(read(p), CoreEvent.class);
      assertThat(e.eventId()).as(p.toString()).isNotBlank(); // CoreEventListener.handle
      assertThat(e.txnId()).as(p.toString()).isNotNull();
      assertThat(e.eventType()).as(p.toString()).isEqualTo(msg);
      assertThat(CORE_EVENTS.toSagaEvent(e, true)).as("saga event for %s", p).isPresent();
    });
    READERS.put("events/mw-orchestrator/mw.partner.reply.v1.schema.json", (p, msg) -> {
      PartnerReply r = JSON.readValue(read(p), PartnerReply.class);
      assertThat(r.requestId()).as(p.toString()).isNotBlank(); // PartnerReplyListener.handle
      assertThat(r.txnId()).as(p.toString()).isNotNull();
      assertThat(r.payload()).as(p.toString()).isNotNull();
      assertThat(r.family()).as(p.toString()).isEqualTo(msg);
    });
    READERS.put("events/mw-orchestrator/mw.partner.inbound.v1.schema.json", (p, msg) -> {
      PartnerInbound in = JSON.readValue(read(p), PartnerInbound.class);
      assertThat(in.inboundId()).as(p.toString()).isNotBlank(); // PartnerInboundListener.handle
      assertThat(in.txnId()).as(p.toString()).isNotNull();
      assertThat(in.payload()).as(p.toString()).isNotNull();
      assertThat(in.family()).as(p.toString()).isEqualTo(msg);
    });
  }

  @TestFactory
  Stream<DynamicTest> everyValidSampleOfAConsumedEventReadsIntoTheOwnType() {
    return ContractManifest.entries("consumes", "events").stream()
        .map(entry -> DynamicTest.dynamicTest(entry, () -> {
          String path = ContractManifest.path(entry);
          String msg = ContractManifest.message(entry);
          assertThat(READERS).as("reader for manifest entry %s", entry).containsKey(path);
          List<Path> samples = ContractSchemas.samples(sampleDir(path, msg), "valid");
          assertThat(samples).as("valid samples of %s", entry).isNotEmpty();
          for (Path sample : samples) {
            READERS.get(path).accept(sample, msg);
          }
        }));
  }

  @Test
  void everyReaderIsForAConsumedSchema() {
    assertThat(ContractManifest.entries("consumes", "events").stream().map(ContractManifest::path).distinct())
        .containsExactlyInAnyOrderElementsOf(READERS.keySet());
  }

  /** {@code events/<owner>/samples/<topic>[/<Msg>]} for a schema {@code events/<owner>/<topic>.schema.json}. */
  private static String sampleDir(String schemaPath, String msg) {
    int slash = schemaPath.lastIndexOf('/');
    String topic = schemaPath.substring(slash + 1).replace(".schema.json", "");
    String dir = schemaPath.substring(0, slash) + "/samples/" + topic;
    return msg == null ? dir : dir + "/" + msg;
  }

  private static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
