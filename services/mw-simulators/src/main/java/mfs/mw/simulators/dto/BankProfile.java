package mfs.mw.simulators.dto;

/**
 * One bank of the simulated bank gateway (ticket 117d), a row of the generated seed file {@code
 * seed/v1/bank-profiles.csv} (a copy of {@code deploy/partner-routes/bank/bank-profiles.csv}): served at
 * {@code /bank/<code>/v1} over its protocol with its auth ({@code hmac-sha256}, {@code api-key}, {@code
 * basic}, {@code ws-security}), mock party {@code bank-<code>}.
 */
public record BankProfile(String code, String name, Protocol protocol, String auth) {

  /** The prefix of every bank's mock party: {@code bank-<code>}. */
  public static final String PARTY_PREFIX = "bank-";

  /** REST under {@code /bank/<code>/v1/...}, or SOAP 1.1 at {@code /bank/<code>/v1/soap}. */
  public enum Protocol { REST, SOAP }

  /** The mock-control party (and journal key) of this bank. */
  public String party() {
    return PARTY_PREFIX + code;
  }
}
