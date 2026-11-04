package mfs.mw.ussd.config;

import io.lettuce.core.ClientOptions;
import mfs.mw.ussd.util.PinCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The core's PIN key (spec 9.2, M11) and the Valkey client. Valkey fails fast while
 * disconnected, so a Valkey outage answers the step with "service unavailable" at once instead
 * of holding the MNO's request until its own timeout.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UssdProperties.class)
public class UssdConfig {

  @Bean
  PinCipher pinCipher(UssdProperties props) {
    return new PinCipher(props.pin().keyId(), props.pin().publicKey());
  }

  @Bean
  LettuceClientOptionsBuilderCustomizer valkeyFailFast() {
    return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
  }
}
