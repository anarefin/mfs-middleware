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
        } catch (PatternSyntaxException e) {
          out.add("fields[" + i + "].regex is not a valid regular expression");
        }
      }
      if (!(f.get("required") instanceof Boolean)) {
        out.add("fields[" + i + "].required is true or false");
      }
    }
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
