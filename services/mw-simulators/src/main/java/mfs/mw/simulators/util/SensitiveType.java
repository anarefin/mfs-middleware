package mfs.mw.simulators.util;

/** Kinds of personal or secret data and how each is masked (spec 18.3). */
public enum SensitiveType {
  /** Mobile number: {@code 0171****789}. */
  MSISDN,
  /** National ID: last 3 digits only. */
  NID,
  /** Bank or wallet account number: last 4 only. */
  ACCOUNT,
  /** Person name: first initial only. */
  NAME,
  /** PIN ciphertext, keys, tokens: never shown. */
  SECRET,
  /**
   * The id of a canonical party, masked by the party's type (ticket 23): in a record with a
   * {@code type} component, {@code WALLET}/{@code MSISDN}/{@code MERCHANT}/{@code ORG} ids that are
   * mobile numbers as {@link #MSISDN}, everything else (bank, biller, card accounts, unknown
   * types) as {@link #ACCOUNT}; on its own always as {@link #ACCOUNT}.
   */
  PARTY_ID
}
