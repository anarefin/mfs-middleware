package mfs.mw.notify.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The push channel (ticket 110, FRS EXT-04), {@code mw.notify.push}: the app-server push API (FCM / APNs
 * style; the dev stack's push mock in mw-simulators).
 *
 * @param enabled false: every push is a transient failure (the message is retried, then FAILED)
 * @param baseUrl where {@code POST /push/v1/messages} lives
 * @param apiKey sent as {@code Authorization: key=<apiKey>}; from Vault ({@code secret/mw-notify}, key
 *     {@code mw.notify.push.api-key}), never in config files
 * @param connectTimeout TCP connect limit
 * @param timeout read limit of one call (each retry gets its own)
 */
@ConfigurationProperties("mw.notify.push")
public record PushProperties(
    boolean enabled, String baseUrl, String apiKey, Duration connectTimeout, Duration timeout) {

  public PushProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://mw-simulators:8080" : baseUrl;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
  }
}
