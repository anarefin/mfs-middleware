package mfs.mw.orchestrator.service.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** MSISDN prefix to MNO, with an MNP override winning over the prefix (FRS PLT-042). */
public final class MsisdnRouting {

  private MsisdnRouting() {}

  /**
   * The MNO of {@code msisdn}. An enabled override of the canonical number wins; otherwise the
   * longest enabled prefix. Empty when the number is not a mobile number or nothing matches.
   */
  public static Optional<String> mno(String msisdn, List<MsisdnPrefix> prefixes, List<MnpOverride> overrides) {
    String canonical = canonical(msisdn);
    if (canonical == null) {
      return Optional.empty();
    }
    for (MnpOverride override : overrides) {
      if (override.enabled() && canonical.equals(canonical(override.msisdn()))) {
        return Optional.of(override.mno());
      }
    }
    return prefixes.stream()
        .filter(MsisdnPrefix::enabled)
        .filter(p -> p.prefix() != null && canonical.startsWith(p.prefix()))
        .max(Comparator.comparingInt(p -> p.prefix().length()))
        .map(MsisdnPrefix::mno);
  }

  /** {@code 01XXXXXXXXX}, from {@code 01…}, {@code 8801…} or {@code +8801…}. */
  static String canonical(String msisdn) {
    if (msisdn == null) {
      return null;
    }
    String digits = msisdn.replaceAll("\\D", "");
    if (digits.startsWith("880") && digits.length() == 13) {
      digits = digits.substring(2);
    }
    if (digits.length() == 10 && digits.startsWith("1")) {
      digits = "0" + digits;
    }
    return digits.length() == 11 && digits.startsWith("01") ? digits : null;
  }
}
