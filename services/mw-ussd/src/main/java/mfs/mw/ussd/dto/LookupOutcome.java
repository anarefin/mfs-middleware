package mfs.mw.ussd.dto;

import java.util.Map;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * The result of an INPUT node's lookup: FOUND with values for the session's inputs, REFUSED with
 * the partner's error (the input is asked again) or UNAVAILABLE (the session ends).
 */
public record LookupOutcome(
    Kind kind, @Sensitive(SensitiveType.SECRET) Map<String, String> values, ErrorInfo error) {

  public enum Kind {
    FOUND,
    REFUSED,
    UNAVAILABLE
  }

  public LookupOutcome {
    values = values == null ? Map.of() : Map.copyOf(values);
  }

  public static LookupOutcome found(Map<String, String> values) {
    return new LookupOutcome(Kind.FOUND, values, null);
  }

  public static LookupOutcome refused(ErrorInfo error) {
    return new LookupOutcome(Kind.REFUSED, Map.of(), error);
  }

  public static LookupOutcome unavailable() {
    return new LookupOutcome(Kind.UNAVAILABLE, Map.of(), null);
  }
}
