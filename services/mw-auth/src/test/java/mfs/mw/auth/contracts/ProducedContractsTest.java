package mfs.mw.auth.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.auth.mapper.AuditEventMapper;
import mfs.mw.auth.mapper.DeviceNotificationMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every event this module produces ({@code produces.events} of {@code contracts.yml}; ticket 68),
 * built by the real mapper from a fixture and serialised as {@code EventPublisher} / {@code
 * OutboxWriter} do ({@code writeValueAsString} on the service's JsonMapper), is valid against its
 * schema. A manifest entry without a fixture fails, and so does a fixture the manifest does not list.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";
  static final Instant NOW = Instant.parse("2026-11-14T06:00:00Z");

  /** Fixtures per manifest entry: the messages the publisher sends on that topic. */
  static final Map<String, Supplier<List<Object>>> FIXTURES =
      Map.of(
          "events/mw-notify/mw.notify.request.v1.schema.json", ProducedContractsTest::notifyRequests,
          "events/mw-audit-dw/mw.audit.event.v1.schema.json", ProducedContractsTest::auditEvents);

  /** {@code AuditTrail}: a PIN lock by mw-auth and an ops unlock (ticket 70). */
  static List<Object> auditEvents() {
    AuditEventMapper mapper = new AuditEventMapper();
    return List.of(
        mapper.event("auth.pin.locked", "01711000001", AuditEventMapper.SYSTEM, "SUCCESS", null,
            Map.of("level", 1, "until", NOW.plusSeconds(3600).toString()), CORRELATION_ID, NOW),
        mapper.event("auth.wallet.unlocked", "01711000001", AuditEventMapper.user("ops-checker01"), "SUCCESS",
            Map.of("adminLock", false, "pinLockLevel", 3), Map.of("action", "UNLOCK", "reason", "verified"),
            CORRELATION_ID, NOW));
  }

  /** {@code DeviceOtpService} (OTP, straight to Kafka) and {@code DeviceBindingService} (outbox). */
  static List<Object> notifyRequests() {
    DeviceNotificationMapper mapper = new DeviceNotificationMapper();
    return List.of(
        mapper.otp("01711000001", "123456", null, NOW.plusSeconds(120), CORRELATION_ID),
        mapper.otp("01711000001", "654321", "en", NOW.plusSeconds(120), CORRELATION_ID),
        mapper.bound("01711000001", "dev-0001", 42L, "bn", CORRELATION_ID),
        mapper.otp("01711000001", "123456", "en", NOW.plusSeconds(120), CORRELATION_ID, "RESET"),
        mapper.notice("01711000001", DeviceNotificationMapper.PIN_CHANGED_TEMPLATE, null, CORRELATION_ID),
        mapper.notice("01711000001", DeviceNotificationMapper.RESET_APPROVED_TEMPLATE, "en", CORRELATION_ID));
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
            assertThat(ContractSchemas.schema(ContractManifest.schemaPath(entry)).validate(tree))
                .as("%s: %s", entry, tree)
                .isEmpty();
          }
        }));
  }

  @Test
  void everyFixtureIsInTheManifest() {
    assertThat(FIXTURES.keySet()).containsExactlyInAnyOrderElementsOf(ContractManifest.load().producedEvents());
  }
}
