package mfs.mw.simulators.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * A push as an app server sends it to the push mock ({@code POST /push/v1/messages}, ticket 110), FCM /
 * APNs style: one device {@code token} or a {@code topic}, the visible {@code notification} and optional
 * {@code data}. Test data only (a device token is not personal data).
 *
 * @param messageId the sender's own id (mw-notify's messageId), kept with the push
 * @param platform ANDROID or IOS, as registered
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PushMessageRequest(
    String messageId, String token, String topic, String platform, Notification notification, Map<String, String> data) {

  /** The visible part: title and body. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Notification(String title, String body) {}
}
