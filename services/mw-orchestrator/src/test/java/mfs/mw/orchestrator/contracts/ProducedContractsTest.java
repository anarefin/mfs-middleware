package mfs.mw.orchestrator.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.mapper.AuditMapper;
import mfs.mw.orchestrator.mapper.CoreCommandMapper;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import mfs.mw.orchestrator.mapper.RequestMapper;
import mfs.mw.orchestrator.mapper.StatusMapper;
import mfs.mw.orchestrator.service.saga.AddMoneyBankFlow;
import mfs.mw.orchestrator.service.saga.BillPayFlow;
import mfs.mw.orchestrator.service.saga.Flow;
import mfs.mw.orchestrator.service.saga.Status;
import mfs.mw.orchestrator.service.saga.TopupFlow;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every event this module produces, per {@code produces.events} of {@code contracts.yml}: built by
 * the real mapper from a fixture, serialised as the outbox does ({@code OutboxWriter}: the service
 * JsonMapper writes the payload, {@code OutboxRelay} re-reads the jsonb and writes it compactly),
 * then validated against the contract; for a {@code #<Msg>} entry the envelope's type field must
 * name that message. A manifest entry without a fixture fails, so does a fixture not in the manifest.
 */
class ProducedContractsTest {

  private static final String STATUS = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String COMMAND = "events/mw-orchestrator/core.command.v1.schema.json";
  private static final String PARTNER_REQUEST = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String AUDIT = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  private static final Duration TTL = Duration.ofSeconds(60);
  private static final Duration PARTNER_DEADLINE = Duration.ofSeconds(30);

  private static final JsonMapper JSON = ContractManifest.serviceJsonMapper();
  private static final RequestMapper REQUESTS = new RequestMapper(JSON);
  private static final CoreCommandMapper COMMANDS = new CoreCommandMapper();
  private static final PartnerRequestMapper PARTNER = new PartnerRequestMapper();

  private static TransactionRecord p2p() {
    return REQUESTS.toRecord(TestData.p2p(TestData.txnId(), "key-p2p", 5_000), "gw-1", TestData.NOW);
  }

  private static TransactionRecord topupHeld() {
    return REQUESTS.toRecord(TestData.topup(TestData.txnId(), "key-top", 2_000, "01711000002"), null, TestData.NOW)
        .moved(Status.VALIDATING, TestData.NOW, null)
        .moved(Status.HOLDING, TestData.NOW, null)
        .withHold("HOLD-1");
  }

  private static TransactionRecord billPay() {
    return REQUESTS.toRecord(TestData.billPay(TestData.txnId(), "key-bill", 120_000, "123456789"), null, TestData.NOW)
        .withHold("HOLD-2");
  }

  private static TransactionRecord addMoney() {
    return REQUESTS.toRecord(TestData.addMoneyBank(TestData.txnId(), "key-c2w", 50_000, "1234567890123"), null, TestData.NOW);
  }

  private static mfs.mw.orchestrator.service.saga.PartnerCall leg(Flow flow) {
    return flow.partnerCall().orElseThrow();
  }

  /** Manifest entry -> the message the module publishes for it. */
  private static final Map<String, Supplier<Object>> FIXTURES = new LinkedHashMap<>();

  static {
    FIXTURES.put(STATUS, () -> new StatusMapper().toStatus(
        p2p().moved(Status.VALIDATING, TestData.NOW, null)
            .moved(Status.POSTING, TestData.NOW, null)
            .withCore("CORE-1", 500L)
            .moved(Status.COMPLETED, TestData.NOW.plusSeconds(1), null)));
    FIXTURES.put(COMMAND + "#ValidateTransaction", () -> COMMANDS.validate(p2p(), TestData.NOW, TTL));
    FIXTURES.put(COMMAND + "#PostTransfer", () -> COMMANDS.post(p2p(), TestData.NOW, TTL));
    FIXTURES.put(COMMAND + "#HoldFunds", () -> COMMANDS.hold(topupHeld(), TestData.NOW, TTL));
    FIXTURES.put(COMMAND + "#CommitHold", () -> COMMANDS.commit(topupHeld(), TestData.NOW, TTL));
    FIXTURES.put(COMMAND + "#ReleaseHold", () -> COMMANDS.release(topupHeld(), TestData.NOW, TTL));
    FIXTURES.put(COMMAND + "#CreditWallet", () -> COMMANDS.credit(addMoney(), TestData.NOW, TTL));
    FIXTURES.put(COMMAND + "#ReverseTransaction", () -> COMMANDS.reverse(
        p2p().withCore("CORE-1", 500L).withReversal(UUID.fromString(TestData.txnId())), TestData.NOW, TTL));
    FIXTURES.put(COMMAND + "#QueryTransaction", () -> COMMANDS.query(topupHeld(), TestData.NOW, TTL));
    FIXTURES.put(PARTNER_REQUEST + "#telco", () -> PARTNER.pay(topupHeld(), leg(new TopupFlow()), TestData.NOW, PARTNER_DEADLINE));
    FIXTURES.put(PARTNER_REQUEST + "#billpay", () -> PARTNER.pay(billPay(), leg(new BillPayFlow()), TestData.NOW, PARTNER_DEADLINE));
    FIXTURES.put(PARTNER_REQUEST + "#bank", () -> PARTNER.pay(addMoney(), leg(new AddMoneyBankFlow()), TestData.NOW, PARTNER_DEADLINE));
    FIXTURES.put(AUDIT, () -> new AuditMapper().stateChanged(
        p2p().moved(Status.VALIDATING, TestData.NOW, null)
            .moved(Status.REJECTED, TestData.NOW, new ErrorInfo("MW-CORE-INSUFFICIENT_BALANCE", "Insufficient balance", null, false)),
        Status.VALIDATING, TestData.NOW));
  }

  /** As OutboxWriter stores it and OutboxRelay sends it. */
  private static JsonNode published(Object message) {
    String stored = JSON.writeValueAsString(message);
    return JSON.readTree(JSON.writeValueAsString(JSON.readTree(stored)));
  }

  @TestFactory
  Stream<DynamicTest> everyProducedEventMatchesItsContract() {
    return ContractManifest.entries("produces", "events").stream()
        .map(entry -> DynamicTest.dynamicTest(entry, () -> {
          assertThat(FIXTURES).as("fixture for manifest entry %s", entry).containsKey(entry);
          JsonNode body = published(FIXTURES.get(entry).get());
          String path = ContractManifest.path(entry);
          assertThat(ContractSchemas.schema(path).validate(body)).as(entry).isEmpty();
          String msg = ContractManifest.message(entry);
          if (msg != null) {
            assertThat(body.path(typeField(path)).asString()).as("message type of %s", entry).isEqualTo(msg);
          }
        }));
  }

  @Test
  void everyFixtureIsInTheManifest() {
    assertThat(ContractManifest.entries("produces", "events")).containsExactlyInAnyOrderElementsOf(FIXTURES.keySet());
  }

  /** The envelope's discriminator: the property besides payload that its oneOf branches pin. */
  private static String typeField(String path) throws Exception {
    JsonNode schema = JSON.readTree(Files.readString(ContractSchemas.contractsDir().resolve(path)));
    for (String name : schema.path("oneOf").path(0).path("properties").propertyNames()) {
      if (!"payload".equals(name)) {
        return name;
      }
    }
    throw new IllegalStateException(path + " is not a multi-type envelope");
  }
}
