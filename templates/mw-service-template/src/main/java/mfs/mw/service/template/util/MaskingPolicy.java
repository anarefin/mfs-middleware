package mfs.mw.service.template.util;

import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masking rules for logs, audit payloads and admin views (spec 18.3, SEC-10). Copied into every
 * service from the template on purpose (spec 4.3 rule 5); the conformance suite keeps the copies
 * aligned.
 */
public final class MaskingPolicy {

  private static final String STARS = "****";

  /** Bangladesh mobile numbers in free text: 01XNNNNNNNN, optionally with 880 / +880. */
  private static final Pattern MSISDN_IN_TEXT =
      Pattern.compile("(?<![\\d+])(?:\\+?880|0)1[3-9]\\d{8}(?!\\d)");

  /**
   * Other standalone runs of 10 to 20 digits (not part of a word, UUID or masked value): 10, 13
   * and 17 digits are Bangladesh NID lengths and keep the last 3; the rest are taken as account
   * numbers and keep the last 4. Ambiguous lengths get the stricter NID rule.
   */
  private static final Pattern LONG_NUMBER_IN_TEXT =
      Pattern.compile("(?<![\\w+*-])\\d{10,20}(?![\\w-])");

  private MaskingPolicy() {}

  /** Masks one value according to its type. {@code null} and empty stay as they are. */
  public static String mask(SensitiveType type, String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    return switch (type) {
      case MSISDN -> value.length() < 8 ? STARS : keepEdges(value, value.length() - 7, 3);
      case NID -> value.length() <= 3 ? STARS : STARS + value.substring(value.length() - 3);
      case ACCOUNT -> value.length() <= 4 ? STARS : STARS + value.substring(value.length() - 4);
      case NAME -> {
        String trimmed = value.strip();
        yield trimmed.isEmpty() ? STARS : trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
      }
      case SECRET -> STARS;
    };
  }

  /**
   * Safety net for free text such as log messages (18.3): masks every Bangladesh MSISDN, then
   * every other 10 to 20 digit number as an NID (10, 13 or 17 digits) or an account number.
   */
  public static String scrub(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String msisdnsMasked = replace(MSISDN_IN_TEXT, text, v -> mask(SensitiveType.MSISDN, v));
    return replace(LONG_NUMBER_IN_TEXT, msisdnsMasked, v -> mask(numberType(v), v));
  }

  private static SensitiveType numberType(String digits) {
    return switch (digits.length()) {
      case 10, 13, 17 -> SensitiveType.NID;
      default -> SensitiveType.ACCOUNT;
    };
  }

  private static String replace(Pattern p, String text, UnaryOperator<String> masker) {
    Matcher m = p.matcher(text);
    StringBuilder out = new StringBuilder(text.length());
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(masker.apply(m.group())));
    }
    m.appendTail(out);
    return out.toString();
  }

  /**
   * Renders a value for logging: records are rendered like {@code Record.toString()} with every
   * {@link Sensitive} component masked (nested records too); other values use {@code toString()}
   * and then {@link #scrub(String)}.
   */
  public static String describe(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Record rec) {
      return describeRecord(rec);
    }
    return scrub(value.toString());
  }

  private static String describeRecord(Record rec) {
    StringJoiner parts = new StringJoiner(", ", rec.getClass().getSimpleName() + "[", "]");
    for (RecordComponent c : rec.getClass().getRecordComponents()) {
      Object v = read(c, rec);
      Sensitive s = c.getAnnotation(Sensitive.class);
      String rendered;
      if (s != null) {
        rendered = v == null ? "null" : mask(s.value(), v.toString());
      } else {
        rendered = describe(v);
      }
      parts.add(c.getName() + "=" + rendered);
    }
    return parts.toString();
  }

  private static Object read(RecordComponent c, Record rec) {
    try {
      var accessor = c.getAccessor();
      accessor.setAccessible(true);
      return accessor.invoke(rec);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return STARS;
    }
  }

  private static String keepEdges(String value, int head, int tail) {
    return value.substring(0, head) + STARS + value.substring(value.length() - tail);
  }
}
