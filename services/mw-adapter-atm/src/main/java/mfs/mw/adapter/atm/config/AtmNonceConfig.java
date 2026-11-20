package mfs.mw.adapter.atm.config;

import mfs.mw.adapter.atm.partner.auth.CallbackNonces;
import mfs.mw.adapter.atm.security.NonceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The WS-Security nonces of the inbound ATM API (ticket 120b1, FRS PLT-008) share the partner
 * framework's replay table {@code callback_nonce} (V7) through {@link CallbackNonces}.
 */
@Configuration(proxyBeanMethods = false)
public class AtmNonceConfig {

  @Bean
  NonceStore atmNonceStore(CallbackNonces nonces) {
    return nonces::firstUse;
  }
}
