package mfs.mw.data.export.service;

/** What may happen to one field before it leaves the service (ticket 128). */
public enum PiiAction {
  /** Copied as it is. Operator usernames and system ids. */
  CLEAR,
  /** Masked with {@link mfs.mw.data.export.util.MaskingPolicy}. */
  MASK,
  /** Replaced by an mw-crypto token. The clear value is not written. */
  TOKENISE
}
