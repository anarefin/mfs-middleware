package mfs.mw.ussd.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.ussd.core}: WAL-01 {@code GET /core/v1/accounts/{msisdn}} for a short code's customer
 * type. {@code mode=stub} (tests) does not call the core.
 */
@ConfigurationProperties("mw.ussd.core")
public record CoreClientProperties(String mode, URI baseUrl, Duration connectTimeout, Duration readTimeout) {

  public CoreClientProperties {
    if (baseUrl == null) {
      baseUrl = URI.create("http://mw-core-mock:8080");
    }
    if (connectTimeout == null) {
      connectTimeout = Duration.ofSeconds(1);
    }
    if (readTimeout == null) {
      readTimeout = Duration.ofSeconds(2);
    }
  }
}
