package mfs.mw.auth.entity;

import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * A row of {@code auth.wallet} (ticket 67, D5): the core's wallet as mw-auth knows it.
 *
 * @param walletId the core's wallet id (mw-core-mock: the MSISDN)
 * @param msisdnToken keyed hash of the MSISDN (mw-crypto TOKENISE_MSISDN), the login lookup key
 * @param msisdnTokenKid the TOKENISE_MSISDN key version of {@code msisdnToken}
 * @param msisdnEnc the MSISDN as an mw-crypto envelope (KEK_COLUMN), JSON
 * @param type wallet type (role in the access token, ticket 69)
 * @param status the core's status: ACTIVE, BLOCKED, HOLD, LOCKED
 * @param hasPin false = first PIN to set
 * @param birthYear when the core supplies it (weak-PIN rule), else null
 * @param version the core's state version; only a higher one replaces the row
 */
public record AuthWallet(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    @Sensitive(SensitiveType.SECRET) String msisdnToken,
    String msisdnTokenKid,
    @Sensitive(SensitiveType.SECRET) String msisdnEnc,
    String type,
    String status,
    boolean hasPin,
    Integer birthYear,
    long version) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
