package mfs.mw.ussd.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.UssdSession;
import mfs.mw.ussd.mapper.TxnRequestMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every event this module produces ({@code produces.events} of {@code contracts.yml}), built by
 * {@link TxnRequestMapper} from a fixture flow and serialised as {@code EventPublisher} does
 * ({@code writeValueAsString} on the service's JsonMapper, from {@code KafkaTransactionFlow}), is
 * valid against its schema. A manifest entry without a fixture fails, and so does a fixture the
 * manifest does not list.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";
  static final EncryptedPin PIN = new EncryptedPin("core-pin-dev-1", "Y2lwaGVy");

  static UssdSession session(String mno, String language) {
    return new UssdSession(mno, "sess-1", "01712345678", "main", 1, language, "x.send", List.of("main:1"),
        Map.of(), 0, List.of(), false, NOW);
  }

  /** Fixtures per manifest entry: the messages the service sends on that topic. */
  static final Map<String, Supplier<List<Object>>> FIXTURES =
      Map.of("events/mw-orchestrator/mw.txn.request.v1.schema.json", ProducedContractsTest::txnRequests);

  /** One request per flow an ACTION node may name ({@code TxnRequestMapper.FLOWS}). */
  static List<Object> txnRequests() {
    TxnRequestMapper mapper = new TxnRequestMapper();
    List<FlowRequest> flows = List.of(
        new FlowRequest("P2P", session("robi", "en"), "main:1",
            Map.of("creditParty", "01812345678", "amount", "500.50"), PIN),
        new FlowRequest("CASH_OUT", session("gp", "bn"), "main:2",
            Map.of("creditParty", "01912345678", "amount", "1000"), PIN),
        new FlowRequest("TOPUP", session("bl", "en"), "main:3",
            Map.of("creditParty", "01612345678", "amount", "50", "partnerId", "gp", "productCode", "PREPAID"), PIN),
        new FlowRequest("BILL_PAY", session("teletalk", "bn"), "main:4",
            Map.of("creditParty", "1234567", "amount", "667", "partnerId", "billagg", "billerCode", "DESCO"), PIN));
    assertThat(flows.stream().map(FlowRequest::flow).toList())
        .containsExactlyInAnyOrderElementsOf(TxnRequestMapper.FLOWS);
    return flows.stream()
        .map(f -> (Object) mapper.toRequest(f, TXN_ID, "k".repeat(64), CORRELATION_ID, NOW))
        .toList();
  }

  @TestFactory
  Stream<DynamicTest> everyProducedEventMatchesItsSchema() {
    return ContractManifest.load().producedEvents().stream()
        .map(entry -> dynamicTest(entry, () -> {
          Supplier<List<Object>> fixture = FIXTURES.get(entry);
          assertThat(fixture).as("fixture for manifest entry %s", entry).isNotNull();
          List<Object> messages = fixture.get();
          assertThat(messages).as("messages for %s", entry).isNotEmpty();
          for (Object message : messages) {
            JsonNode tree = JSON.readTree(JSON.writeValueAsString(message));
            assertThat(ContractSchemas.schema(ContractManifest.path(entry)).validate(tree))
                .as("%s: %s", entry, tree)
                .isEmpty();
            String msg = ContractManifest.msg(entry);
            if (msg != null) {
              assertThat(tree.path(ContractManifest.typeField(entry)).asString()).isEqualTo(msg);
            }
          }
        }));
  }

  @Test
  void everyFixtureIsInTheManifest() {
    assertThat(FIXTURES.keySet())
        .containsExactlyInAnyOrderElementsOf(ContractManifest.load().producedEvents());
  }
}
