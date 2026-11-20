package mfs.mw.admin.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks a biller catalogue value (ticket 113, contract {@code BillerValue} of {@code
 * mw.config.changed.v1}) before a change request is opened or a seed stored. Messages never repeat
 * a value.
 */
final class BillerRules {

  static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{2,32}$");
  private static final Pattern AGGREGATOR = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  private static final Pattern FIELD_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]{0,31}$");
  private static final Set<String> CATEGORIES = Set.of("UTILITY", "OTHER");
  private static final Set<String> RULES = Set.of("FIXED", "MINIMUM", "OPEN");

  private BillerRules() {}

  static List<String> problems(Map<String, Object> v) {
    List<String> out = new ArrayList<>();
    if (v == null) {
      out.add("value is required");
      return out;
    }
    text(v, "nameEn", 200, true, out);
    text(v, "nameBn", 200, true, out);
    if (!CATEGORIES.contains(String.valueOf(v.get("category")))) {
      out.add("category is UTILITY or OTHER");
    }
    if (!(v.get("primaryAggregator") instanceof String p) || !AGGREGATOR.matcher(p).matches()) {
      out.add("primaryAggregator is a partner id");
    }
    Object fallback = v.get("fallbackAggregator");
    if (fallback != null && (!(fallback instanceof String f) || !AGGREGATOR.matcher(f).matches())) {
      out.add("fallbackAggregator is a partner id");
    } else if (fallback != null && fallback.equals(v.get("primaryAggregator"))) {
      out.add("fallbackAggregator differs from primaryAggregator");
    }
    fields(v.get("fields"), out);
    amount(v, out);
    if (!(v.get("billMonthRequired") instanceof Boolean)) {
      out.add("billMonthRequired is true or false");
    }
    if (!(v.get("enabled") instanceof Boolean enabled)) {
      out.add("enabled is true or false");
    } else {
      boolean need = !enabled;
      text(v, "maintenanceEn", 500, need, out);
      text(v, "maintenanceBn", 500, need, out);
    }
    return out;
  }

  private static void fields(Object raw, List<String> out) {
    if (!(raw instanceof List<?> list) || list.size() > 10) {
      out.add("fields is a list of at most 10 input fields");
      return;
    }
    for (int i = 0; i < list.size(); i++) {
      if (!(list.get(i) instanceof Map<?, ?> f)) {
        out.add("fields[" + i + "] is an object");
        continue;
      }
      if (!(f.get("name") instanceof String n) || !FIELD_NAME.matcher(n).matches()) {
        out.add("fields[" + i + "].name is a field name");
      }
      for (String label : List.of("labelEn", "labelBn")) {
        if (!(f.get(label) instanceof String s) || s.isBlank() || s.length() > 100) {
          out.add("fields[" + i + "]." + label + " is 1 to 100 characters");
        }
      }
      if (!(f.get("regex") instanceof String r) || r.isBlank() || r.length() > 200) {
        out.add("fields[" + i + "].regex is 1 to 200 characters");
      } else {
        try {
          Pattern.compile(r);
          if (!safe(r)) {
            out.add("fields[" + i + "].regex is outside the allowed pattern grammar (anchors, literals, classes, bounded quantifiers)");
          }
        } catch (PatternSyntaxException e) {
          out.add("fields[" + i + "].regex is not a valid regular expression");
        }
      }
      if (!(f.get("required") instanceof Boolean)) {
        out.add("fields[" + i + "].required is true or false");
      }
    }
  }

  /** Most quantifiers a field regex may hold, and the largest {@code {m,n}} bound. */
  static final int MAX_QUANTIFIERS = 8;

  static final int MAX_BOUND = 64;

  /**
   * Whether a field regex fits the strict whitelist grammar (security review of ticket 113: the
   * channels match customer input against it, so nothing may backtrack catastrophically): an
   * optional {@code ^}, a sequence of atoms, an optional {@code $}. An atom is a literal character
   * (punctuation escaped with {@code \}), a character class {@code [...]} (literals, ranges and
   * {@code \d \w \s} or escaped punctuation inside, no nesting), {@code \d}, {@code \w}, {@code \s}
   * or {@code .}, with at most one quantifier: {@code ?}, {@code *}, {@code +}, {@code {n}} or
   * {@code {m,n}} with n at most 64. No groups, alternation, backreferences, lookarounds or other
   * escapes; at most 8 quantifiers in all. Called after the pattern compiled.
   */
  static boolean safe(String regex) {
    int i = 0;
    int n = regex.length();
    int quantifiers = 0;
    if (i < n && regex.charAt(i) == '^') {
      i++;
    }
    while (i < n) {
      char c = regex.charAt(i);
      if (c == '$' && i == n - 1) {
        return true;
      }
      // atom
      if (c == '\\') {
        if (i + 1 >= n || !escapeAllowed(regex.charAt(i + 1))) {
          return false;
        }
        i += 2;
      } else if (c == '[') {
        i = classEnd(regex, i);
        if (i < 0) {
          return false;
        }
      } else if ("()|?*+{}^$]".indexOf(c) >= 0) {
        return false;
      } else {
        i++;
      }
      // optional quantifier
      if (i < n) {
        char q = regex.charAt(i);
        if (q == '?' || q == '*' || q == '+') {
          i++;
          quantifiers++;
        } else if (q == '{') {
          int end = regex.indexOf('}', i);
          if (end < 0 || !boundAllowed(regex.substring(i + 1, end))) {
            return false;
          }
          i = end + 1;
          quantifiers++;
        }
        if (quantifiers > MAX_QUANTIFIERS) {
          return false;
        }
      }
    }
    return true;
  }

  /** {@code \d \w \s} or an escaped punctuation character. */
  private static boolean escapeAllowed(char e) {
    return e == 'd' || e == 'w' || e == 's' || (e < 128 && !Character.isLetterOrDigit(e) && !Character.isWhitespace(e));
  }

  /** {@code n} or {@code m,n} with m <= n <= 64. */
  private static boolean boundAllowed(String body) {
    if (!body.matches("[0-9]{1,2}(,[0-9]{1,2})?")) {
      return false;
    }
    String[] parts = body.split(",");
    int low = Integer.parseInt(parts[0]);
    int high = Integer.parseInt(parts[parts.length - 1]);
    return low <= high && high <= MAX_BOUND;
  }

  /** The index after the class starting at {@code start}, or -1 when it holds what the grammar refuses. */
  private static int classEnd(String regex, int start) {
    int i = start + 1;
    if (i < regex.length() && regex.charAt(i) == '^') {
      i++;
    }
    boolean empty = true;
    while (i < regex.length()) {
      char c = regex.charAt(i);
      if (c == ']' && !empty) {
        return i + 1;
      }
      if (c == '[' || c == '&') {
        return -1;
      }
      if (c == '\\') {
        if (i + 1 >= regex.length() || !escapeAllowed(regex.charAt(i + 1))) {
          return -1;
        }
        i += 2;
      } else {
        i++;
      }
      empty = false;
    }
    return -1;
  }

  private static void amount(Map<String, Object> v, List<String> out) {
    String rule = String.valueOf(v.get("amountRule"));
    if (!RULES.contains(rule)) {
      out.add("amountRule is FIXED, MINIMUM or OPEN");
      return;
    }
    Long min = minor(v, "minAmountMinor", out);
    Long max = minor(v, "maxAmountMinor", out);
    if ("MINIMUM".equals(rule) && min == null) {
      out.add("minAmountMinor is required for MINIMUM");
    }
    if (min != null && max != null && max < min) {
      out.add("maxAmountMinor is at least minAmountMinor");
    }
  }

  private static Long minor(Map<String, Object> v, String key, List<String> out) {
    Object raw = v.get(key);
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number n && n.doubleValue() == n.longValue() && n.longValue() >= 0) {
      return n.longValue();
    }
    out.add(key + " is a whole number of poisha, 0 or more");
    return null;
  }

  private static void text(Map<String, Object> v, String key, int max, boolean required, List<String> out) {
    Object raw = v.get(key);
    if (raw == null) {
      if (required) {
        out.add(key + " is required");
      }
      return;
    }
    if (!(raw instanceof String s) || s.isBlank() || s.length() > max) {
      out.add(key + " is 1 to " + max + " characters");
    }
  }
}
