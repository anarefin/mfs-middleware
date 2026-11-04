package mfs.mw.app.gateway.config;

import io.lettuce.core.ClientOptions;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceBindingInterceptor;
import mfs.mw.app.gateway.service.DeviceBindingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The device gate on {@code /app/**} (spec 9.1, 18.6, ticket 29): the interceptor (the body it
 * hashes is cached by {@code SubmitSchemaConfig}'s filter). On unless {@code mw.app-gateway.device-binding.enabled=false} (tests
 * not about it). Valkey (sessions, nonces) fails fast while disconnected; the gate then answers
 * 503 MW-SYS-012 instead of letting requests through, and Valkey is kept out of the health groups
 * so the rest of the API (none, today) is not taken out of readiness with it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeviceBindingProperties.class)
public class DeviceBindingConfig {

  @Bean
  LettuceClientOptionsBuilderCustomizer valkeyFailFast() {
    return b -> b.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "mw.app-gateway.device-binding.enabled", havingValue = "true", matchIfMissing = true)
  static class Gate implements WebMvcConfigurer {

    private final DeviceBindingService devices;
    private final AppUserResolver users;

    Gate(DeviceBindingService devices, AppUserResolver users) {
      this.devices = devices;
      this.users = users;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
      registry.addInterceptor(new DeviceBindingInterceptor(devices, users)).addPathPatterns("/app/**");
    }
  }
}
