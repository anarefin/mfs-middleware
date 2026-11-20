package mfs.mw.adapter.atm.config;

import mfs.mw.adapter.atm.repository.AtmNonceRepository;
import mfs.mw.adapter.atm.security.NonceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The WS-Security nonces of the inbound ATM API (tickets 120b1, 120b2, FRS PLT-008) live in their
 * own table {@code atm_ws_nonce} (V10) with a per-row expiry on the database clock, so the partner
 * callbacks' purge of {@code callback_nonce} cannot free a live ATM nonce.
 */
@Configuration(proxyBeanMethods = false)
public class AtmNonceConfig {

  @Bean
  NonceStore atmNonceStore(AtmNonceRepository nonces) {
    return nonces::firstUse;
  }
}
