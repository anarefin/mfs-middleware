package mfs.mw.checkout.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.checkout.dto.EncryptedPin;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.mapper.CheckoutMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every event this module produces ({@code produces.events} of {@code contracts.yml}), built by
 * {@link CheckoutMapper} from a fixture session and serialised as {@code EventPublisher} (the pay
 * request) / {@code OutboxWriter} (the OTP SMS) do ({@code writeValueAsString} on the service's
 * JsonMapper), is valid against its schema. A manifest entry without a fixture fails, and so
 * does a fixture the manifest does not list.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
  static final UUID SESSION = UUID.fromString("019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c01");
  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";

  static CheckoutSession session(String locale, String description) {
    return new CheckoutSession(
        SESSION, "tok", "merchant_demo", "01911000001", "ORD-42", 2550, "BDT", description, locale,
        "https://shop.example.test/ok", "https://shop.example.test/fail", "https://shop.example.test/cancel",
        "https://shop.example.test/ipn", SessionStatus.PAYING, "01711000001", "hash", NOW.plusSeconds(120),
        0, 1, null, null, NOW, NOW.plusSeconds(900), NOW);
  }

  /** Fixtures per manifest entry: the messages the service sends on that topic. */
  static final Map<String, Supplier<List<Object>>> FIXTURES =
      Map.of(
          "events/mw-orchestrator/mw.txn.request.v1.schema.json", ProducedContractsTest::txnRequests,
          "events/mw-notify/mw.notify.request.v1.schema.json", ProducedContractsTest::notifyRequests);

  /** {@code CheckoutPayService.confirm}: the MERCHANT_PAY request. */
  static List<Object> txnRequests() {
    CheckoutMapper mapper = new CheckoutMapper();
    EncryptedPin pin = new EncryptedPin("core-pin-dev-1", "Y2lwaGVy");
    return List.of(
        mapper.toTxnRequest(session("en", "Order 42"), TXN_ID, CORRELATION_ID, pin, NOW),
        mapper.toTxnRequest(session("bn", null), TXN_ID, CORRELATION_ID, pin, NOW));
  }

  /** {@code CheckoutPayService.sendOtp}: the OTP SMS (outbox). */
  static List<Object> notifyRequests() {
    CheckoutMapper mapper = new CheckoutMapper();
    return List.of(
        mapper.toOtpNotification(session("en", "Order 42"), "Demo Shop", "123456", 1, NOW.plusSeconds(120),
            CORRELATION_ID),
        mapper.toOtpNotification(session("bn", null), "Demo Shop", "654321", 3, NOW.plusSeconds(120),
            CORRELATION_ID));
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
