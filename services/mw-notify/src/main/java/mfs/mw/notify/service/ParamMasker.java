package mfs.mw.notify.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import mfs.mw.notify.util.MaskingPolicy;
import mfs.mw.notify.util.SensitiveType;

/**
 * Masks template params per field policy before rendering (spec 10.3, 18.3): a param whose name
 * is in the policy is masked by its type, others pass as they are (amounts, references, the OTP,
 * which is the message itself). Masking is idempotent, so a producer may send params already
 * masked ({@code 0181****002}).
 */
public final class ParamMasker {

  private static final Map<String, SensitiveType> DEFAULTS =
      Map.ofEntries(
          Map.entry("msisdn", SensitiveType.MSISDN),
          Map.entry("receiver", SensitiveType.MSISDN),
          Map.entry("sender", SensitiveType.MSISDN),
          Map.entry("counterparty", SensitiveType.MSISDN),
          Map.entry("agent", SensitiveType.MSISDN),
          Map.entry("account", SensitiveType.ACCOUNT),
          Map.entry("accountnumber", SensitiveType.ACCOUNT),
          Map.entry("nid", SensitiveType.NID),
          Map.entry("name", SensitiveType.NAME),
          Map.entry("receivername", SensitiveType.NAME),
          Map.entry("sendername", SensitiveType.NAME),
          Map.entry("pin", SensitiveType.SECRET));

  private final Map<String, SensitiveType> policy;

  public ParamMasker(Map<String, SensitiveType> policy) {
    Map<String, SensitiveType> lower = new LinkedHashMap<>();
    policy.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
    this.policy = Map.copyOf(lower);
  }

  /** The built-in field policy (param name, case-insensitive, to masking type). */
  public static ParamMasker defaults() {
    return new ParamMasker(DEFAULTS);
  }

  public Map<String, String> mask(Map<String, String> params) {
    Map<String, String> out = new LinkedHashMap<>();
    params.forEach(
        (k, v) -> {
          SensitiveType type = policy.get(k.toLowerCase(Locale.ROOT));
          out.put(k, type == null || alreadyMasked(v) ? v : MaskingPolicy.mask(type, v));
        });
    return out;
  }

  /**
   * A value that already carries the mask ({@code ****}) passes as it is: the rule engine masks the
   * other party by its type (an account keeps its last 4, an MSISDN its edges), which masking it
   * again by name would change.
   */
  private static boolean alreadyMasked(String v) {
    return v != null && v.contains("****");
  }
}
