package mfs.mw.auth.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RFC 8785 (JCS) canonical JSON for the small value set the transaction hash needs (spec 18.7,
 * D22): objects (keys sorted by UTF-16 code units), arrays, strings, booleans, null and integral
 * numbers. A fractional number is refused: amounts are integer minor units. mw-core-mock keeps an
 * identical copy (D14) and the two must agree byte for byte.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  private static void append(StringBuilder out, Object v) {
    switch (v) {
      case null -> out.append("null");
      case String s -> string(out, s);
      case Boolean b -> out.append(b);
      case Integer i -> out.append(i);
      case Long l -> out.append(l);
      case Short s -> out.append(s);
      case BigInteger b -> out.append(b);
      case Map<?, ?> m -> object(out, m);
      case List<?> l -> array(out, l);
      default -> throw new IllegalArgumentException("not a canonical JSON value: " + v.getClass().getSimpleName());
    }
  }

  private static void object(StringBuilder out, Map<?, ?> m) {
    List<String> keys = new ArrayList<>();
    for (Object k : m.keySet()) {
      keys.add((String) k);
    }
    keys.sort(null); // String.compareTo compares UTF-16 code units, as RFC 8785 3.2.3 asks
    out.append('{');
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      string(out, keys.get(i));
      out.append(':');
      append(out, m.get(keys.get(i)));
    }
    out.append('}');
  }

  private static void array(StringBuilder out, List<?> l) {
    out.append('[');
    for (int i = 0; i < l.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      append(out, l.get(i));
    }
    out.append(']');
  }

  /** ECMAScript JSON.stringify string escaping (RFC 8785 3.2.2.2). */
  private static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
