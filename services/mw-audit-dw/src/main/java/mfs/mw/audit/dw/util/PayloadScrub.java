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
   * Blanks the content of every secret element, whole, in one linear pass that fails closed (no
   * regex, no recursion, no depth bound):
   *
   * <ul>
   *   <li>comments and CDATA sections are blanked whole ({@code <!--****-->}, {@code
   *       <![CDATA[****]]>}); declarations ({@code <!DOCTYPE ...>}) and processing instructions other
   *       than the XML declaration too ({@code <!****>}, {@code <?****?>}); an unterminated one blanks
   *       the rest of the body;
   *   <li>a {@code <} that does not start a well-formed tag (a stray {@code <}, an unmatched quote, a
   *       {@code <} inside an attribute value) is copied as text and the scan goes on right after it;
   *   <li>at a secret opening tag everything up to the close tag of that very element is blanked:
   *       nested elements are tracked by name and a close tag that matches neither the innermost open
   *       element nor the secret element is ignored, so blanking goes on; an element that is never
   *       closed blanks the rest of the body.
   * </ul>
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
      Markup m = markup(text, lt);
      switch (m.kind()) {
        case STRAY -> {
          out.append('<');
          i = lt + 1;
        }
        case UNTERMINATED -> {
          out.append(m.prefix()).append(STARS);
          return out.toString();
        }
        case BLANKED -> {
          out.append(m.prefix()).append(STARS).append(m.suffix());
          i = m.end();
        }
        case OPEN -> {
          out.append(text, lt, m.end());
          if (isSecretKey(localName(m.name()))) {
            out.append(STARS);
            int close = closeOf(text, m.end(), m.name());
            if (close < 0) {
              return out.toString(); // never closed: the rest is blanked
            }
            out.append("</").append(m.name()).append('>');
            i = close;
          } else {
            i = m.end();
          }
        }
        default -> { // CLOSE, EMPTY, XML_DECL
          out.append(text, lt, m.end());
          i = m.end();
        }
      }
    }
    return out.toString();
  }

  private enum Kind { STRAY, UNTERMINATED, BLANKED, OPEN, CLOSE, EMPTY, XML_DECL }

  /** One piece of markup at a {@code <}: its kind, element name, end (exclusive) and blanked frame. */
  private record Markup(Kind kind, String name, int end, String prefix, String suffix) {}

  private static final Pattern XML_DECLARATION =
      Pattern.compile("<\\?xml(?:\\s+[A-Za-z]+\\s*=\\s*(?:\"[^\"<>]*\"|'[^'<>]*'))*\\s*\\?>");

  private static Markup markup(String text, int lt) {
    if (text.startsWith("<!--", lt)) {
      return blanked(text, lt, "<!--", "-->");
    }
    if (text.startsWith("<![CDATA[", lt)) {
      return blanked(text, lt, "<![CDATA[", "]]>");
    }
    if (text.startsWith("<?", lt)) {
      int e = text.indexOf("?>", lt + 2);
      if (e < 0) {
        return new Markup(Kind.UNTERMINATED, null, text.length(), "<?", "");
      }
      Matcher decl = XML_DECLARATION.matcher(text).region(lt, e + 2);
      return decl.matches()
          ? new Markup(Kind.XML_DECL, null, e + 2, "", "")
          : new Markup(Kind.BLANKED, null, e + 2, "<?", "?>");
    }
    if (text.startsWith("<!", lt)) {
      int e = declarationEnd(text, lt + 2);
      return e < 0
          ? new Markup(Kind.UNTERMINATED, null, text.length(), "<!", "")
          : new Markup(Kind.BLANKED, null, e + 1, "<!", ">");
    }
    boolean closing = text.startsWith("</", lt);
    int nameStart = closing ? lt + 2 : lt + 1;
    int j = nameStart;
    while (j < text.length() && isNameChar(text.charAt(j), j == nameStart)) {
      j++;
    }
    if (j == nameStart) {
      return stray();
    }
    String name = text.substring(nameStart, j);
    int end = tagEnd(text, j);
    if (end < 0) {
      return stray();
    }
    if (closing) {
      return new Markup(Kind.CLOSE, name, end + 1, "", "");
    }
    return new Markup(text.charAt(end - 1) == '/' ? Kind.EMPTY : Kind.OPEN, name, end + 1, "", "");
  }

  private static Markup stray() {
    return new Markup(Kind.STRAY, null, -1, "", "");
  }

  private static Markup blanked(String text, int lt, String open, String close) {
    int e = text.indexOf(close, lt + open.length());
    return e < 0
        ? new Markup(Kind.UNTERMINATED, null, text.length(), open, "")
        : new Markup(Kind.BLANKED, null, e + close.length(), open, close);
  }

  private static boolean isNameChar(char c, boolean first) {
    return Character.isLetter(c) || c == '_' || c == ':' || (!first && (Character.isDigit(c) || c == '-' || c == '.'));
  }

  /**
   * Index of the {@code >} ending the tag whose name ends at {@code from}, or -1 when the tag is not
   * well formed: a {@code <} before it (XML forbids one in attribute values too) or an unmatched quote.
   * Stops at the next {@code <}, so the whole scan stays linear.
   */
  private static int tagEnd(String text, int from) {
    char quote = 0;
    for (int j = from; j < text.length(); j++) {
      char c = text.charAt(j);
      if (c == '<') {
        return -1;
      }
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return j;
      }
    }
    return -1;
  }

  /** End of a declaration ({@code <!DOCTYPE ... [ ... ]>}), brackets counted; -1 when unterminated. */
  private static int declarationEnd(String text, int from) {
    int brackets = 0;
    for (int j = from; j < text.length(); j++) {
      char c = text.charAt(j);
      if (c == '[') {
        brackets++;
      } else if (c == ']' && brackets > 0) {
        brackets--;
      } else if (c == '>' && brackets == 0) {
        return j;
      }
    }
    return -1;
  }

  private static String localName(String name) {
    int colon = name.lastIndexOf(':');
    return colon < 0 ? name : name.substring(colon + 1);
  }

  /**
   * Index just after the close tag of the secret element {@code secret} whose content starts at
   * {@code from}, or -1 when it is never closed. Nested elements are tracked by name; a close tag that
   * matches neither the innermost open element nor (with none open) the secret element is ignored.
   * Comments, CDATA and the rest are skipped; an unterminated one means never closed.
   */
  private static int closeOf(String text, int from, String secret) {
    java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
    int i = from;
    while (true) {
      int lt = text.indexOf('<', i);
      if (lt < 0) {
        return -1;
      }
      Markup m = markup(text, lt);
      switch (m.kind()) {
        case STRAY -> i = lt + 1;
        case UNTERMINATED -> {
          return -1;
        }
        case OPEN -> {
          open.push(m.name());
          i = m.end();
        }
        case CLOSE -> {
          if (!open.isEmpty() && open.peek().equals(m.name())) {
            open.pop();
          } else if (open.isEmpty() && m.name().equals(secret)) {
            return m.end();
          }
          i = m.end();
        }
        default -> i = m.end();
      }
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
