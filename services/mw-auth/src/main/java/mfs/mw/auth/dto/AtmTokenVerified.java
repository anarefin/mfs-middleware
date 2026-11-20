package mfs.mw.auth.dto;

import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** Answer of a spent ATM token: the wallet to debit (ticket 120b2). */
public record AtmTokenVerified(@Sensitive(SensitiveType.MSISDN) String walletId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
