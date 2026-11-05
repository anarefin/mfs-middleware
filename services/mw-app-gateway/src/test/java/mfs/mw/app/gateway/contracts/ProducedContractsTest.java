package mfs.mw.app.gateway.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.PartnerRef;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.mapper.DeviceNotificationMapper;
import mfs.mw.app.gateway.mapper.RequestMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every event this module produces ({@code produces.events} of {@code contracts.yml}), built by
 * the real mapper from a fixture and serialised as {@code EventPublisher} / {@code OutboxWriter}
 * do ({@code writeValueAsString} on the service's JsonMapper), is valid against its schema. A
 * manifest entry without a fixture fails, and so does a fixture the manifest does not list.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";
  static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");
  static final AppUser CUSTOMER = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER");
  static final AppUser AGENT = new AppUser("01811000001", "AGENT_APP", "AGENT");
  static final EncryptedPin PIN = new EncryptedPin("core-pin-1", "b3BhcXVlLWNpcGhlcnRleHQ=");
  static final Amount AMOUNT = new Amount(12_345L, "BDT");

  /** Fixtures per manifest entry: the messages the publisher sends on that topic. */
  static final Map<String, Supplier<List<Object>>> FIXTURES =
      Map.of(
          "events/mw-orchestrator/mw.txn.request.v1.schema.json", ProducedContractsTest::txnRequests,
          "events/mw-notify/mw.notify.request.v1.schema.json", ProducedContractsTest::notifyRequests);

  /** {@code TransactionService.submit}: one request per supported txnType. */
  static List<Object> txnRequests() {
    RequestMapper mapper = new RequestMapper();
    return List.of(
        mapper.toRequest(CUSTOMER, "key-p2p",
            new TransactionRequest("P2P", new TransactionRequest.CounterParty(null, "01711000002"), AMOUNT, PIN,
                "en", Map.of("reference", "lunch")),
            TXN_ID, CORRELATION_ID, NOW),
        mapper.toRequest(AGENT, "key-cash-in",
            new TransactionRequest("CASH_IN", new TransactionRequest.CounterParty("WALLET", "01711000002"), AMOUNT, PIN,
                null, null),
            TXN_ID, CORRELATION_ID, NOW),
        mapper.toRequest(CUSTOMER, "key-cash-out",
            new TransactionRequest("CASH_OUT", new TransactionRequest.CounterParty(null, "01811000001"), AMOUNT, PIN,
                "bn", null),
            TXN_ID, CORRELATION_ID, NOW),
        mapper.toRequest(CUSTOMER, "key-merchant",
            new TransactionRequest("MERCHANT_PAY", new TransactionRequest.CounterParty(null, "01911000001"), AMOUNT,
                PIN, null, null),
            TXN_ID, CORRELATION_ID, NOW),
        mapper.toRequest(CUSTOMER, "key-topup",
            new TransactionRequest("TOPUP", new TransactionRequest.CounterParty("MSISDN", "01711000003"), AMOUNT, PIN,
                null, null, new TransactionRequest.Partner("gp", "PREPAID")),
            TXN_ID, CORRELATION_ID, NOW),
        mapper.toRequest(CUSTOMER, "key-bill",
            new TransactionRequest("BILL_PAY", new TransactionRequest.CounterParty("BILLER_ACCOUNT", "1234567"),
                AMOUNT, PIN, null, null, TransactionRequest.Partner.biller("DESCO")),
            TXN_ID, CORRELATION_ID, NOW, new PartnerRef("billagg", "BILL", "DESCO")),
        mapper.toRequest(CUSTOMER, "key-c2w",
            new TransactionRequest("ADD_MONEY_BANK", null, AMOUNT, PIN, null, null,
                TransactionRequest.Partner.bank("c2wbank"),
                new TransactionRequest.BankAccount("BANK_ACCOUNT", "1234567890123")),
            TXN_ID, CORRELATION_ID, NOW));
  }

  /** {@code DeviceOtpService} (OTP, straight to Kafka) and {@code DeviceBindingService} (outbox). */
  static List<Object> notifyRequests() {
    DeviceNotificationMapper mapper = new DeviceNotificationMapper();
    return List.of(
        mapper.otp("01711000001", "123456", null, NOW.plusSeconds(120), CORRELATION_ID),
        mapper.otp("01711000001", "654321", "en", NOW.plusSeconds(120), CORRELATION_ID),
        mapper.bound("01711000001", "bn", CORRELATION_ID));
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
