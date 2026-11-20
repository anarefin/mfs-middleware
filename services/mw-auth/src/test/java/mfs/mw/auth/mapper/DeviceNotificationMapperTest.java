package mfs.mw.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import mfs.mw.auth.contracts.ContractSchemas;
import mfs.mw.auth.dto.NotificationRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The device binding SMS are valid mw.notify.request.v1 records (contract owned by mw-notify). */
class DeviceNotificationMapperTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String SCHEMA = "events/mw-notify/mw.notify.request.v1.schema.json";

  final DeviceNotificationMapper mapper = new DeviceNotificationMapper();

  @Test
  void theOtpSmsHasOtpPriorityAndExpiresWithTheOtp() {
    Instant expires = Instant.parse("2026-11-05T08:02:00Z");
    NotificationRequest sms = mapper.otp("01711000001", "042917", "en", expires, "corr-1");
    JsonNode tree = JSON.valueToTree(sms);
    assertThat(ContractSchemas.schema(SCHEMA).validate(tree)).isEmpty();
    assertThat(sms.templateCode()).isEqualTo("DEVICE_BIND_OTP");
    assertThat(sms.priority()).isEqualTo("OTP");
    assertThat(sms.expiresAt()).isEqualTo(expires);
    assertThat(sms.params()).containsEntry("otp", "042917");
  }

  @Test
  void theBoundNoticeIsATxnSmsInBanglaByDefaultNamingTheNewDevice() {
    NotificationRequest sms = mapper.bound("01711000001", "dev-0001", 42L, null, "corr-1");
    assertThat(ContractSchemas.schema(SCHEMA).validate(JSON.valueToTree(sms))).isEmpty();
    assertThat(sms.templateCode()).isEqualTo("DEVICE_BOUND");
    assertThat(sms.priority()).isEqualTo("TXN");
    assertThat(sms.locale()).isEqualTo("bn");
    // mw-notify revokes the push tokens of the wallet's other devices (ticket 110) and orders bindings and
    // token registrations by the binding epoch (ticket 110b); the SMS shows nothing of either
    assertThat(sms.params()).containsOnly(
        java.util.Map.entry("deviceId", "dev-0001"), java.util.Map.entry("bindingEpoch", "42"));
  }

  @Test
  void everyMessageIsANewOne() {
    assertThat(mapper.bound("01711000001", "dev-1", 7L, "en", "c").messageId())
        .isNotEqualTo(mapper.bound("01711000001", "dev-1", 7L, "en", "c").messageId());
  }
}
