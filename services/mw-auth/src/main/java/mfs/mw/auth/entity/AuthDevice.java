package mfs.mw.auth.entity;

import java.time.Instant;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * A row of {@code auth.device} (ticket 68): a device bound to a wallet with its P-256 public key
 * (base64 X.509 SubjectPublicKeyInfo, a public key, not personal data).
 *
 * @param unboundAt null while bound; set when another device of the wallet was bound
 * @param bindingEpoch the epoch of the device's current (or last) binding (ticket 110b): one sequence over every
 *     binding, so a later binding has a higher epoch whatever the clocks
 */
public record AuthDevice(
    String deviceId,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String publicKey,
    Instant boundAt,
    Instant unboundAt,
    long bindingEpoch) {

  public boolean bound() {
    return unboundAt == null;
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
