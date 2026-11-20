package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/**
 * {@code mw.notify.push-token.v1} (contract {@code events/mw-notify/mw.notify.push-token.v1}, owned by this
 * service; ticket 110): the device token the app registered for its wallet's bound device.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PushTokenEvent(
    int schemaVersion,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String deviceId,
    @Sensitive(SensitiveType.PUSH_TOKEN) String token,
    Platform platform,
    Instant registeredAt,
    String correlationId) {

  /** The app's platform (FCM for ANDROID, APNs for IOS). */
  public enum Platform {
    ANDROID,
    IOS
  }
}
