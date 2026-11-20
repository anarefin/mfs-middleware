package mfs.mw.checkout.config;

import mfs.mw.checkout.security.MerchantReplayInterceptor;
import mfs.mw.checkout.service.RequestReplayGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Replay protection on the merchant API (ticket 99, FRS PLT-008): {@code
 * security/MerchantReplayInterceptor} on {@code /checkout/v1/sessions}. On unless {@code
 * mw.checkout.replay-protection.enabled=false} (tests not about it, which send no nonce).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mw.checkout.replay-protection.enabled", havingValue = "true", matchIfMissing = true)
public class ReplayProtectionConfig implements WebMvcConfigurer {

  private final RequestReplayGuard guard;

  public ReplayProtectionConfig(RequestReplayGuard guard) {
    this.guard = guard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new MerchantReplayInterceptor(guard)).addPathPatterns("/checkout/v1/sessions", "/checkout/v1/payments/*/refunds");
  }
}
