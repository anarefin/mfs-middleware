package mfs.mw.adapter.telco.dto;

/** A definite answer from the OCS. Anything less definite is an exception (see exception/). */
public sealed interface OcsResult {

  /** Top-up applied; {@code ocsRef} is the OCS' own reference. */
  record Accepted(String ocsRef, String code) implements OcsResult {}

  /** Top-up refused for a business reason; nothing was applied. */
  record Rejected(String code, String message) implements OcsResult {}

  /** The OCS answered with a code that does not say whether the top-up was applied. */
  record Ambiguous(String code, String message) implements OcsResult {}
}
