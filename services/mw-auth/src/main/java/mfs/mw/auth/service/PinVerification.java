package mfs.mw.auth.service;

import mfs.mw.auth.dto.EncryptedPin;

/**
 * Checking a wallet's PIN and counting a failure (spec 18.7, D20): the one seam the transaction
 * assertion ({@link TxnAuthorisationService}) uses. Implemented by {@link PinVerifier} (ticket 70:
 * the locks checked before mw-crypto, the lockout levels), the same check login and device binding
 * make.
 */
public interface PinVerification {

  /**
   * Verifies the PIN through mw-crypto; a locked wallet is refused first (403 MW-AUTH-043 ADMIN lock,
   * 423 MW-AUTH-041 / MW-AUTH-042 PIN lock), a wrong PIN is counted and refused 401 MW-AUTH-040 (the
   * one that locks: the lock), no PIN 403 MW-AUTH-045, mw-crypto down 503 MW-SYS-050.
   */
  void verify(String walletId, EncryptedPin pin);
}
