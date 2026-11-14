package mfs.mw.auth.service;

import mfs.mw.auth.dto.EncryptedPin;

/**
 * Checking a wallet's PIN and counting a failure (spec 18.7, D20): the one seam the transaction
 * assertion ({@link TxnAuthorisationService}) uses. Today {@link PinService} with ticket 69's simple
 * counter; ticket 70's lockout escalation replaces the implementation behind it without touching
 * the callers.
 */
public interface PinVerification {

  /**
   * Verifies the PIN through mw-crypto; a wrong one is counted and refused 401 MW-AUTH-040, no PIN
   * 403 MW-AUTH-045, mw-crypto down 503 MW-SYS-050.
   */
  void verify(String walletId, EncryptedPin pin);
}
