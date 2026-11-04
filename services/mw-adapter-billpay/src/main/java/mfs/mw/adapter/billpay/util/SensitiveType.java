package mfs.mw.adapter.billpay.util;

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
  SECRET
}
