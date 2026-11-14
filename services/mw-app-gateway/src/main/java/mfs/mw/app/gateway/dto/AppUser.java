package mfs.mw.app.gateway.dto;

import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * The authenticated app user, taken from the mw-auth token: the wallet MSISDN (claim {@code
 * msisdn}), the channel and the wallet type that follow from the role, and the device the token was
 * issued to (claim {@code deviceId}; the device gate checks it is the request's {@code X-Device-Id}),
 * which mw-auth's txn-authorisation checks against the wallet's bound device (ticket 72).
 */
public record AppUser(
    @Sensitive(SensitiveType.MSISDN) String msisdn, String channel, String walletType, String deviceId) {

  /** A user without a token device (tests of what does not reach mw-auth). */
  public AppUser(String msisdn, String channel, String walletType) {
    this(msisdn, channel, walletType, null);
  }
}
