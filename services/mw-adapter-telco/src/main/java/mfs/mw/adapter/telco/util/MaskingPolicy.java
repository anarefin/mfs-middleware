package mfs.mw.adapter.telco.util;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
   * Other runs of 10 or more digits, bounded by anything but a digit (a number glued to a word,
   * {@code _} or {@code -} is still masked, and no run is too long to mask): 10, 13 and 17 digits
   * are Bangladesh NID lengths and keep the last 3; the rest are taken as account numbers and keep
   * the last 4. Ambiguous lengths get the stricter NID rule. UUIDs and trace/span ids are protected
   * separately ({@link #PROTECTED_ID}), not by these bounds.
   */
  private static final Pattern LONG_NUMBER_IN_TEXT = Pattern.compile("(?<!\\d)\\d{10,}(?!\\d)");

  /**
   * Ids that are not personal data and are left as they are, so their digit runs are not taken
   * for numbers: UUIDs (8-4-4-4-12 hex; the dashes cap a digit run at 12) and W3C trace/span
   * ids (32 or 16 lowercase hex, standalone or as the parts of a {@code traceparent}) whose
   * longest digit run is under 10, so the hex shape alone never hides a number. Bounded by
   * anything but a letter or digit; an id glued to letters or digits is not protected and its
   * digits get scrubbed.
   */
  private static final Pattern PROTECTED_ID =
      Pattern.compile(
          "(?<![0-9A-Za-z])"
              + "(?:[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}"
              + "|(?![0-9a-f]*\\d{10})(?:[0-9a-f]{32}|[0-9a-f]{16}))"
              + "(?![0-9A-Za-z])");

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
   * Safety net for free text such as log messages (18.3): outside UUIDs and trace/span ids, masks
   * every Bangladesh MSISDN, then every other number of 10 or more digits as an NID (10, 13 or 17
   * digits) or an account number.
   */
  public static String scrub(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    Matcher ids = PROTECTED_ID.matcher(text);
    StringBuilder out = new StringBuilder(text.length());
    int from = 0;
    while (ids.find()) {
      out.append(scrubNumbers(text.substring(from, ids.start()))).append(ids.group());
      from = ids.end();
    }
    return out.append(scrubNumbers(text.substring(from))).toString();
  }

  private static String scrubNumbers(String text) {
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
   * {@link Sensitive} component masked; arrays, {@link Collection}s, {@link Map}s (keys and
   * values) and {@link Optional}s are walked like their own {@code toString()} so records inside
   * them are masked too, at any depth. Every other value, including iterables that are not
   * collections such as {@code Path} or {@code JsonNode}, uses {@code toString()} and then {@link
   * #scrub(String)}. A container that contains itself is rendered as {@code (this)}.
   */
  public static String describe(Object value) {
    return describe(value, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private static String describe(Object value, Set<Object> inProgress) {
    if (value == null) {
      return "null";
    }
    boolean container =
        value instanceof Record
            || value instanceof Collection<?>
            || value instanceof Map<?, ?>
            || value instanceof Optional<?>
            || value.getClass().isArray();
    if (!container) {
      return scrub(value.toString());
    }
    if (!inProgress.add(value)) {
      return "(this)";
    }
    try {
      return describeContainer(value, inProgress);
    } finally {
      inProgress.remove(value);
    }
  }

  private static String describeContainer(Object value, Set<Object> inProgress) {
    if (value instanceof Record rec) {
      return describeRecord(rec, inProgress);
    }
    if (value instanceof Optional<?> opt) {
      return opt.map(v -> "Optional[" + describe(v, inProgress) + "]").orElse("Optional.empty");
    }
    StringJoiner parts;
    if (value instanceof Map<?, ?> map) {
      parts = new StringJoiner(", ", "{", "}");
      for (Map.Entry<?, ?> e : map.entrySet()) {
        parts.add(describe(e.getKey(), inProgress) + "=" + describe(e.getValue(), inProgress));
      }
      return parts.toString();
    }
    parts = new StringJoiner(", ", "[", "]");
    if (value instanceof Collection<?> items) {
      for (Object item : items) {
        parts.add(describe(item, inProgress));
      }
    } else {
      for (int i = 0; i < Array.getLength(value); i++) {
        parts.add(describe(Array.get(value, i), inProgress));
      }
    }
    return parts.toString();
  }

  private static String describeRecord(Record rec, Set<Object> inProgress) {
    StringJoiner parts = new StringJoiner(", ", rec.getClass().getSimpleName() + "[", "]");
    for (RecordComponent c : rec.getClass().getRecordComponents()) {
      Object v = read(c, rec);
      Sensitive s = c.getAnnotation(Sensitive.class);
      String rendered;
      if (s != null) {
        rendered = v == null ? "null" : mask(s.value(), v.toString());
      } else {
        rendered = describe(v, inProgress);
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
