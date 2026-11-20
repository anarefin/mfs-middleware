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
 * @param type the core wallet type (ORGANISATION is stored as ORG)
 * @param status the core's status: ACTIVE, BLOCKED, HOLD, LOCKED
 * @param hasPin false = first PIN to set
 * @param birthYear when the core supplies it (weak-PIN rule), else null
 * @param version the core's state version; only a higher one replaces the row
 * @param appRole a seeder mark, or null. Only {@code PARTNER} on an ORG wallet changes the app login
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
    long version,
    String appRole) {

  /** A wallet the projection built: no seeder mark. */
  public AuthWallet(
      String walletId, String msisdnToken, String msisdnTokenKid, String msisdnEnc, String type, String status,
      boolean hasPin, Integer birthYear, long version) {
    this(walletId, msisdnToken, msisdnTokenKid, msisdnEnc, type, status, hasPin, birthYear, version, null);
  }

  /**
   * The app type of a login. The stored core type stays as it is. An organisation wallet the seeder
   * marked as a partner user logs in as {@code PARTNER}; every other wallet uses its core type, so an
   * unmarked organisation still has no app.
   */
  public String appType() {
    if ("ORG".equals(type) && "PARTNER".equals(appRole)) {
      return "PARTNER";
    }
    return type;
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
