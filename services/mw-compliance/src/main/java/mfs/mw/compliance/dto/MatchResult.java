package mfs.mw.compliance.dto;

/**
 * The outcome of comparing the customer's data with the EC record.
 *
 * @param level FULL when matched, null otherwise
 * @param score best normalised name similarity, 0..1
 * @param reason why it is not matched ({@code DOB_MISMATCH}, {@code NID_NOT_FOUND}, {@code NAME_MISMATCH}), else null
 */
public record MatchResult(KycLevel level, double score, String reason) {

  /** The DOB matches but the name is below the threshold (product decision, ticket 124b). */
  public static final String NAME_MISMATCH = "NAME_MISMATCH";

  public boolean matched() {
    return level != null;
  }

  /** Held for an operator (ticket 134): a DOB-only match is never auto-issued a level. */
  public boolean manualReview() {
    return NAME_MISMATCH.equals(reason);
  }
}
