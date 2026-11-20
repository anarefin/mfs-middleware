package mfs.mw.audit.dw.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The defensive scrub of a hop payload before it is stored in {@code audit.payload} (ticket 130,
 * FRS AUD-01, CON-04, spec 18.3). Producers mask first; this runs again on write so a PIN, a card
 * PAN or a clear MSISDN/NID/account number never lands in the audit store:
 *
 * <ul>
 *   <li>secret fields are blanked to {@code ****}, the whole value (an object, array or element
 *       subtree too): JSON properties at any depth, XML elements and {@code key=value} / {@code key:
 *       value} pairs. A key is secret when one of its words ({@link #isSecretKey}: split at
 *       camelCase, digits and any non-letter) is a secret word ({@code pin}, {@code mpin}, {@code
 *       tpin}, {@code pinblock}, {@code pincode}, {@code otp}, {@code cvv}, {@code cvc}, {@code
 *       secret}, {@code pwd}, {@code passcode}) or starts with {@code passw}; so {@code userPin},
 *       {@code pin_code}, {@code walletPassword}, {@code otpCode} are secret and {@code spinner},
 *       {@code shipping}, {@code options} are not;
 *   <li>a card PAN (13-19 digits passing the Luhn check, with or without single spaces or dashes)
 *       keeps its last 4 only;
 *   <li>then {@link MaskingPolicy#scrub(String)} masks MSISDNs and every other long number, in JSON
 *       values and property names alike.
 * </ul>
 *
 * A JSON body that does not parse is scrubbed as text.
 */
public final class PayloadScrub {

  private static final String STARS = "****";

  /** Words that make a key secret (lower case, trailing digits dropped). */
  private static final java.util.Set<String> SECRET_WORDS =
      java.util.Set.of(
          "pin", "mpin", "tpin", "pinblock", "pincode", "pinhash", "otp", "totp", "cvv", "cvc", "secret",
          "pwd", "passwd", "passcode", "passphrase");

  /** Word boundaries in a key: camelCase humps, letter/digit changes, anything not a letter or digit. */
  private static final Pattern WORD_SPLIT =
      Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

  /** An element (optionally prefixed) with its attributes, its content (any depth) and its close tag. */
  private static final Pattern XML_ELEMENT =
      Pattern.compile("<((?:[A-Za-z_][\\w.-]*:)?([A-Za-z_][\\w.-]*))(\\s[^>]*)?>(.*?)</\\1\\s*>", Pattern.DOTALL);

  /** {@code key=...}, {@code "key": "..."}, {@code key: ...} in text that is not parsed JSON. */
  private static final Pattern PAIR =
      Pattern.compile("((?<![A-Za-z0-9_.-])\"?([A-Za-z_][A-Za-z0-9_.-]*)\"?\\s*[=:]\\s*\"?)([^&\"',;\\s<}]+)");

  /** 13-19 digits, each pair joined by at most one space or dash. */
  private static final Pattern PAN = Pattern.compile("(?<![\\d])\\d(?:[ -]?\\d){12,18}(?![\\d])");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PayloadScrub() {}

  /**
   * @param contentType the body's media type ({@code application/json} bodies are walked)
   * @return the scrubbed body; {@code null} and empty stay as they are
   */
  public static String scrub(String contentType, String body) {
    if (body == null || body.isEmpty()) {
      return body;
    }
    if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("json")) {
      try {
        JsonNode tree = JSON.readTree(body);
        if (tree != null && (tree.isObject() || tree.isArray())) {
          return JSON.writeValueAsString(walk(tree));
        }
      } catch (JacksonException e) {
        // not JSON after all: scrubbed as text below
      }
    }
    return text(body);
  }

  private static String text(String body) {
    String out = xml(body, 0);
    out = replace(PAIR, out, m -> isSecretKey(m.group(2)) ? m.group(1) + STARS : m.group());
    return MaskingPolicy.scrub(pans(out));
  }

  /** Blanks the content of every secret element, whole; walks into the others (bounded depth). */
  private static String xml(String text, int depth) {
    if (depth > 32 || text.indexOf('<') < 0) {
      return text;
    }
    return replace(
        XML_ELEMENT,
        text,
        m -> {
          String open = "<" + m.group(1) + (m.group(3) == null ? "" : m.group(3)) + ">";
          String close = "</" + m.group(1) + ">";
          return open + (isSecretKey(m.group(2)) ? STARS : xml(m.group(4), depth + 1)) + close;
        });
  }

  /**
   * Whether a property, element or parameter name names a secret: one of its words (split at
   * camelCase, digits kept on their word and dropped at its end, any other character) is in {@link
   * #SECRET_WORDS} or starts with {@code passw}.
   */
  static boolean isSecretKey(String key) {
    if (key == null || key.isEmpty()) {
      return false;
    }
    for (String word : WORD_SPLIT.split(key)) {
      String w = word.toLowerCase(java.util.Locale.ROOT).replaceAll("[0-9]+$", "");
      if (SECRET_WORDS.contains(w) || w.startsWith("passw")) {
        return true;
      }
    }
    return false;
  }

  private static String value(String v) {
    return MaskingPolicy.scrub(pans(v));
  }

  private static JsonNode walk(JsonNode node) {
    if (node instanceof ObjectNode obj) {
      ObjectNode out = obj.objectNode();
      obj.properties()
          .forEach(
              p -> {
                String name = unusedName(out, value(p.getKey()));
                JsonNode v = p.getValue();
                out.set(
                    name,
                    isSecretKey(p.getKey()) && !v.isNull()
                        ? obj.stringNode(STARS)
                        : walk(v));
              });
      return out;
    }
    if (node instanceof ArrayNode arr) {
      ArrayNode out = arr.arrayNode();
      arr.forEach(v -> out.add(walk(v)));
      return out;
    }
    if (node.isString() || node.isNumber()) {
      String text = node.asString();
      String scrubbed = value(text);
      return text.equals(scrubbed) ? node : JSON.getNodeFactory().stringNode(scrubbed);
    }
    return node;
  }

  private static String unusedName(ObjectNode out, String name) {
    String candidate = name;
    for (int n = 2; out.has(candidate); n++) {
      candidate = name + "#" + n;
    }
    return candidate;
  }

  private static String pans(String text) {
    return replace(
        PAN,
        text,
        m -> {
          String digits = m.group().replaceAll("[ -]", "");
          return luhn(digits) ? STARS + digits.substring(digits.length() - 4) : m.group();
        });
  }

  static boolean luhn(String digits) {
    int sum = 0;
    boolean twice = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (twice) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      twice = !twice;
    }
    return sum % 10 == 0;
  }

  private static String replace(Pattern p, String text, java.util.function.Function<Matcher, String> f) {
    Matcher m = p.matcher(text);
    StringBuilder out = new StringBuilder(text.length());
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(f.apply(m)));
    }
    m.appendTail(out);
    return out.toString();
  }
}
