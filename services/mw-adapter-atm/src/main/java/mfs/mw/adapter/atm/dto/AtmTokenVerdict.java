package mfs.mw.adapter.atm.dto;

import mfs.mw.adapter.atm.util.MaskingPolicy;
import mfs.mw.adapter.atm.util.Sensitive;
import mfs.mw.adapter.atm.util.SensitiveType;

/**
 * mw-auth's verdict on an ATM token (ticket 120b2): spent, with the wallet to debit, or refused with
 * mw-auth's code (wrong, expired or spent, locked, wallet blocked).
 */
public record AtmTokenVerdict(boolean accepted, @Sensitive(SensitiveType.MSISDN) String walletId, String reason) {

  public static AtmTokenVerdict accepted(String walletId) {
    return new AtmTokenVerdict(true, walletId, null);
  }

  public static AtmTokenVerdict declined(String reason) {
    return new AtmTokenVerdict(false, null, reason);
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
