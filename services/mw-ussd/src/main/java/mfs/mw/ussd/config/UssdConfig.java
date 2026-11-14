package mfs.mw.ussd.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Valkey client (the PIN transport key comes from mw-auth since ticket 72:
 * {@code service/PinTransport}). Valkey fails fast while
 * disconnected, so a Valkey outage answers the step with "service unavailable" at once instead
 * of holding the MNO's request until its own timeout.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UssdProperties.class)
public class UssdConfig {

  @Bean
  LettuceClientOptionsBuilderCustomizer valkeyFailFast() {
    return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
  }
}
