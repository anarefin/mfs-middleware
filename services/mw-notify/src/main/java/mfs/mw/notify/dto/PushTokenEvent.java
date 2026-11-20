package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/**
 * {@code mw.notify.push-token.v1} (contract {@code events/mw-notify/mw.notify.push-token.v1}, owned by this
 * service; ticket 110): the device token the app registered for its wallet's bound device.
 *
 * @param registeredAt the gateway's clock: orders two registrations of one binding only
 * @param bindingEpoch mw-auth's epoch of this device's binding to this wallet (ticket 110b), compared with the
 *     bindings mw-notify knows of (mw-auth's {@code DEVICE_BOUND} notice, param {@code bindingEpoch}); one
 *     source (mw-auth's database sequence), so no clock skew between services lets a late registration of an
 *     older binding through
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PushTokenEvent(
    int schemaVersion,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String deviceId,
    @Sensitive(SensitiveType.PUSH_TOKEN) String token,
    Platform platform,
    Instant registeredAt,
    Long bindingEpoch,
    String correlationId) {

  /** The app's platform (FCM for ANDROID, APNs for IOS). */
  public enum Platform {
    ANDROID,
    IOS
  }
}
