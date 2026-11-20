package mfs.mw.compliance.dto;

/**
 * The outcome of comparing the customer's data with the EC record.
 *
 * @param level FULL or BASIC when matched, null when mismatched
 * @param score best normalised name similarity, 0..1
 * @param reason why it is mismatched ({@code DOB_MISMATCH}, {@code NID_NOT_FOUND}), else null
 */
public record MatchResult(KycLevel level, double score, String reason) {

  public boolean matched() {
    return level != null;
  }
}
