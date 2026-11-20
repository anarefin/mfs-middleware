package mfs.mw.recon.dto;

/** Per-source match rule (ticket 126). Tolerance is absolute poisha; the date window is ± days. */
public record MatchRule(long toleranceMinor, int dateWindowDays) {

  public MatchRule {
    if (toleranceMinor < 0) {
      throw new IllegalArgumentException("tolerance must be >= 0");
    }
    if (dateWindowDays < 0) {
      throw new IllegalArgumentException("date window must be >= 0");
    }
  }
}
