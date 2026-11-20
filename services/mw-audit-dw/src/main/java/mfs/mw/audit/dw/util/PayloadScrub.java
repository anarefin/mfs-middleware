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
          "pin", "mpin", "tpin", "otp", "totp", "cvv", "cvc", "secret", "pwd", "passwd", "password",
          "passcode", "passphrase");

  /** What may follow a secret word inside one word ({@code pinblock}, {@code otpcode}, {@code pincipher}). */
  private static final java.util.List<String> SECRET_SUFFIXES =
      java.util.List.of(
          "", "block", "code", "cipher", "ciphertext", "hash", "value", "data", "number", "no", "text", "enc",
          "encrypted");

  /**
   * What may come before a secret word inside one word ({@code newpin}, {@code encryptedpin},
   * {@code userpassword}); stripped repeatedly ({@code encryptednewpin}).
   */
  private static final java.util.List<String> SECRET_PREFIXES =
      java.util.List.of(
          "encrypted", "current", "confirm", "customer", "wallet", "login", "agent", "user", "card", "txn",
          "new", "old", "enc", "m", "t");

  /** The pre-ticket whole-key rule, kept as an extra check (case-insensitive). */
  private static final Pattern LEGACY_NAME =
      Pattern.compile(
          "(?i)(?:new|old|current|encrypted)?_?(?:pin|pin_?block|pin_?cipher(?:text)?|password|passwd|otp|cvv2?|cvc|secret)");

  /** The longest body scanned; a longer one is stored as {@code ****} (fail closed, bounded work). */
  static final int MAX_SCAN = 1 << 20;

  /** Word boundaries in a key: camelCase humps, letter/digit changes, anything not a letter or digit. */
  private static final Pattern WORD_SPLIT =
      Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

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
    if (body.length() > MAX_SCAN) {
      return STARS;
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
    String out = xml(body);
    out = replace(PAIR, out, m -> isSecretKey(m.group(2)) ? m.group(1) + STARS : m.group());
    return MaskingPolicy.scrub(pans(out));
  }

  /**
   * Blanks the content of every secret element, whole, in one linear pass (no regex, no recursion,
   * no depth bound): a tokenizer walks the tags; at a secret opening tag it skips to the matching
   * close by counting nested tags and writes {@code ****} instead. An element that is never closed
   * blanks everything after its opening tag (fail closed). Comments, CDATA, processing instructions
   * and declarations are copied as text (the text scrubs still run over them).
   */
  static String xml(String text) {
    if (text.indexOf('<') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    int n = text.length();
    int i = 0;
    while (i < n) {
      int lt = text.indexOf('<', i);
      if (lt < 0) {
        out.append(text, i, n);
        break;
      }
      out.append(text, i, lt);
      int end = tagEnd(text, lt);
      if (end < 0) {
        out.append(text, lt, n);
        break;
      }
      String name = openingName(text, lt, end);
      if (name != null && isSecretKey(localName(name))) {
        out.append(text, lt, end + 1).append(STARS);
        int close = matchingClose(text, end + 1);
        if (close < 0) {
          return out.toString(); // never closed: the rest is blanked
        }
        out.append("</").append(name).append('>');
        i = close;
      } else {
        out.append(text, lt, end + 1);
        i = end + 1;
      }
    }
    return out.toString();
  }

  /** Index of the {@code >} that ends the markup starting at {@code lt}, or -1. */
  private static int tagEnd(String text, int lt) {
    if (text.startsWith("<!--", lt)) {
      int e = text.indexOf("-->", lt + 4);
      return e < 0 ? -1 : e + 2;
    }
    if (text.startsWith("<![CDATA[", lt)) {
      int e = text.indexOf("]]>", lt + 9);
      return e < 0 ? -1 : e + 2;
    }
    char quote = 0;
    for (int j = lt + 1; j < text.length(); j++) {
      char c = text.charAt(j);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return j;
      } else if (c == '<') {
        return -1; // not a tag: a stray '<' inside text
      }
    }
    return -1;
  }

  /** The element name of an opening, not self-closing tag {@code text[lt..end]}, else null. */
  private static String openingName(String text, int lt, int end) {
    if (lt + 1 >= end || text.charAt(end - 1) == '/') {
      return null;
    }
    char first = text.charAt(lt + 1);
    if (!(Character.isLetter(first) || first == '_')) {
      return null; // closing tag, comment, CDATA, declaration, processing instruction
    }
    int j = lt + 1;
    while (j < end && !Character.isWhitespace(text.charAt(j)) && text.charAt(j) != '/') {
      j++;
    }
    return text.substring(lt + 1, j);
  }

  private static String localName(String name) {
    int colon = name.lastIndexOf(':');
    return colon < 0 ? name : name.substring(colon + 1);
  }

  /**
   * Index just after the closing tag that ends the element whose content starts at {@code from}
   * (nesting counted over every element), or -1 when it is never closed.
   */
  private static int matchingClose(String text, int from) {
    int depth = 1;
    int i = from;
    while (true) {
      int lt = text.indexOf('<', i);
      if (lt < 0) {
        return -1;
      }
      int end = tagEnd(text, lt);
      if (end < 0) {
        return -1;
      }
      if (text.startsWith("</", lt)) {
        if (--depth == 0) {
          return end + 1;
        }
      } else if (openingName(text, lt, end) != null) {
        depth++;
      }
      i = end + 1;
    }
  }

  /**
   * Whether a property, element or parameter name names a secret: the whole key matches the
   * pre-ticket rule ({@link #LEGACY_NAME}), or one of its words (split at camelCase and any other
   * character than a letter or digit; trailing digits dropped) is a secret word, alone or inside
   * one word: known prefixes ({@link #SECRET_PREFIXES}, repeatedly) before it and a known suffix
   * ({@link #SECRET_SUFFIXES}) after it ({@code newpin}, {@code encryptedpin}, {@code pincipher},
   * {@code otpcode}), or a word that starts with {@code passw} after the prefixes.
   */
  static boolean isSecretKey(String key) {
    if (key == null || key.isEmpty() || key.length() > 256) {
      return key != null && key.length() > 256;
    }
    if (LEGACY_NAME.matcher(key).matches()) {
      return true;
    }
    for (String word : WORD_SPLIT.split(key)) {
      if (secretWord(word.toLowerCase(java.util.Locale.ROOT).replaceAll("[0-9]+$", ""))) {
        return true;
      }
    }
    return false;
  }

  private static boolean secretWord(String w) {
    if (w.isEmpty()) {
      return false;
    }
    if (w.startsWith("passw")) {
      return true;
    }
    for (String secret : SECRET_WORDS) {
      if (w.startsWith(secret) && SECRET_SUFFIXES.contains(w.substring(secret.length()))) {
        return true;
      }
    }
    for (String prefix : SECRET_PREFIXES) {
      if (w.length() > prefix.length() && w.startsWith(prefix) && secretWord(w.substring(prefix.length()))) {
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
