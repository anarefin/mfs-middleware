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
 *   <li>secret fields (PIN, PIN block, password, OTP, CVV, secret) are blanked to {@code ****}: JSON
 *       properties at any depth, XML elements and {@code key=value} / {@code key: value} pairs;
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

  /** Property, element or parameter names whose value is a secret. */
  private static final String NAME =
      "(?:new|old|current|encrypted)?_?(?:pin|pin_?block|pin_?cipher(?:text)?|password|passwd|otp|cvv2?|cvc|secret)";

  private static final Pattern SECRET_NAME = Pattern.compile("(?i)" + NAME);

  /** {@code <pin>...</pin>}. */
  private static final Pattern XML_SECRET =
      Pattern.compile("(?i)(<(" + NAME + ")(?:\\s[^>]*)?>)([^<]*)(</\\2>)");

  /** {@code pin=...}, {@code "pin": "..."}, {@code pin: ...} in text that is not parsed JSON. */
  private static final Pattern PAIR_SECRET =
      Pattern.compile("(?i)((?<![A-Za-z0-9])\"?" + NAME + "\"?\\s*[=:]\\s*\"?)([^&\"',;\\s<}]+)");

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
    String out = replace(XML_SECRET, body, m -> m.group(1) + STARS + m.group(4));
    out = replace(PAIR_SECRET, out, m -> m.group(1) + STARS);
    return MaskingPolicy.scrub(pans(out));
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
                    SECRET_NAME.matcher(p.getKey()).matches() && !v.isContainer() && !v.isNull()
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
