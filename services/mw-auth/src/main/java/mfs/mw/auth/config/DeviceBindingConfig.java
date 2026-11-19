package mfs.mw.auth.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Device binding (spec 9.1, 18.7, ticket 68): its settings and Redis (sessions, nonces) failing fast while
 * disconnected, so the device API answers 503 MW-SYS-012 instead of waiting; Redis is kept out of
 * the health groups ({@code application.yml}) so the internal API is not taken out of readiness
 * with it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DeviceBindingProperties.class, TokenProperties.class})
public class DeviceBindingConfig {

  @Bean
  LettuceClientOptionsBuilderCustomizer redisFailFast() {
    return b -> b.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
  }
}
