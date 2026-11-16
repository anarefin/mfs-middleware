package mfs.mw.auth.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import mfs.mw.auth.dto.TestAccount;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dev-only account test API {@code api/mw-auth/auth-test.v1} (the test console): the account
 * view the controller answers with, serialised as Spring MVC writes it, and the PIN body the console
 * sends validate against its schemas. No Spring context, no Docker.
 */
class AccountTestApiContractsTest {

  static final String TEST_API = "api/mw-auth/auth-test.v1.openapi.yaml";
  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final Instant NOW = Instant.parse("2026-11-15T06:00:00Z");

  @Test
  void aFullAccountMatchesTheContract() {
    ApiContractsTest.assertValid(OpenApiSchemas.component(TEST_API, "TestAccount"),
        new TestAccount("01711000001", "ACTIVE", true, true, NOW.plusSeconds(60), 0,
            new TestAccount.PinLock(1, NOW.plusSeconds(60), true), false,
            new TestAccount.DeviceOtp("BIND", 2, 1, NOW.plusSeconds(120), true), 1,
            List.of(new TestAccount.Device("dev-00000002", "BOUND", NOW, null),
                new TestAccount.Device("dev-00000001", "UNBOUND", NOW.minusSeconds(600), NOW))));
  }

  @Test
  void aBareAccountLeavesTheOptionalPartsOut() {
    TestAccount bare = new TestAccount("01711000001", "ACTIVE", false, false, null, 0, null, false, null, 0, List.of());
    ApiContractsTest.assertValid(OpenApiSchemas.component(TEST_API, "TestAccount"), bare);
    assertThat(JSON.writeValueAsString(bare)).doesNotContain("lockedUntil", "pinLock", "deviceOtp", "null");
  }

  @Test
  void aPermanentPinLockHasNoEnd() {
    ApiContractsTest.assertValid(OpenApiSchemas.component(TEST_API, "TestAccount"),
        new TestAccount("01711000001", "ACTIVE", true, true, null, 0, new TestAccount.PinLock(3, null, true), false,
            null, 0, List.of()));
  }

  @Test
  void thePinBodyMatchesTheContract() {
    var schema = OpenApiSchemas.component(TEST_API, "TestPinRequest");
    assertThat(schema.validate(JSON.readTree(
        "{\"encryptedPin\":{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\"AAAA\"}}"))).isEmpty();
    assertThat(schema.validate(JSON.readTree("{\"pin\":\"1234\"}"))).as("a clear PIN is refused").isNotEmpty();
  }

  @Test
  void errorsAreTheEnvelope() {
    assertThat(OpenApiSchemas.component(TEST_API, "ErrorEnvelope").validate(JSON.readTree(
        "{\"code\":\"MW-VAL-003\",\"message\":\"Unknown wallet\",\"correlationId\":\"c\",\"retryable\":false,"
            + "\"details\":[]}"))).isEmpty();
  }
}
