package mfs.mw.adapter.atm.security;

import java.time.Duration;

/**
 * Once-only WS-Security nonces (ticket 120b1, FRS PLT-008). In the service it is the partner
 * framework's {@code partner/auth/CallbackNonces} (table {@code callback_nonce}); an interface so
 * the SOAP slice test needs no database.
 */
@FunctionalInterface
public interface NonceStore {

  /** True the first time {@code partnerId} sends {@code nonce} within {@code window}. */
  boolean firstUse(String partnerId, String nonce, Duration window);
}
