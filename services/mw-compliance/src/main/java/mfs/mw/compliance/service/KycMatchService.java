package mfs.mw.compliance.service;

import java.time.LocalDate;
import mfs.mw.compliance.config.ComplianceProperties;
import mfs.mw.compliance.dto.EcPerson;
import mfs.mw.compliance.dto.KycLevel;
import mfs.mw.compliance.dto.MatchResult;
import mfs.mw.compliance.util.NameNormaliser;
import org.springframework.stereotype.Service;

/**
 * The match rule table (ticket 124): the date of birth must be exactly the EC's; then the better of the English
 * and the Bangla name similarity (each normalised: {@link NameNormaliser}) at or above {@code
 * mw.compliance.kyc.name-threshold} gives {@code FULL}, below it {@code BASIC}. Another date of birth is
 * MISMATCHED (manual review in the console, ticket 134).
 */
@Service
public class KycMatchService {

  public static final String DOB_MISMATCH = "DOB_MISMATCH";
  public static final String NID_NOT_FOUND = "NID_NOT_FOUND";

  private final double threshold;

  public KycMatchService(ComplianceProperties props) {
    this.threshold = props.kyc().nameThreshold();
  }

  public MatchResult match(LocalDate dob, String nameEn, String nameBn, EcPerson ec) {
    double score = Math.max(NameNormaliser.similarity(nameEn, ec.nameEn()), NameNormaliser.similarity(nameBn, ec.nameBn()));
    if (ec.dob() == null || !ec.dob().equals(dob)) {
      return new MatchResult(null, score, DOB_MISMATCH);
    }
    return new MatchResult(score >= threshold ? KycLevel.FULL : KycLevel.BASIC, score, null);
  }

  /** The EC has no such NID. */
  public static MatchResult notFound() {
    return new MatchResult(null, 0, NID_NOT_FOUND);
  }
}
