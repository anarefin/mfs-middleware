package mfs.mw.orchestrator.util;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A JSON body masked for the audit trail (ticket 130b, FRS AUD-01; spec 18.3): a secret field
 * (assertion, PIN, OTP, token, password, secret) is blanked whole, a name field keeps its first
 * initial, every other text or number goes through {@link MaskingPolicy#scrub}. The result is cut
 * to {@link #MAX_CHARS}; mw-audit-dw scrubs it again on write.
 */
public final class AuditBodies {

  /** The excerpt size the adapters keep too (4 KB). */
  public static final int MAX_CHARS = 4096;

  private static final Set<String> SECRET_WORDS =
      Set.of("assertion", "pin", "mpin", "pinblock", "otp", "token", "secret", "password", "passcode", "cvv");

  private AuditBodies() {}

  /** The masked JSON text of {@code body}, null for none. */
  public static String mask(JsonNode body) {
    if (body == null || body.isNull() || body.isMissingNode()) {
      return null;
    }
    String text = masked(body).toString();
    return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS);
  }

  private static JsonNode masked(JsonNode node) {
    JsonNodeFactory f = JsonNodeFactory.instance;
    if (node.isObject()) {
      ObjectNode out = f.objectNode();
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        out.set(e.getKey(), field(e.getKey(), e.getValue()));
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = f.arrayNode();
      node.values().forEach(v -> out.add(masked(v)));
      return out;
    }
    if (node.isString()) {
      return f.stringNode(MaskingPolicy.scrub(node.asString()));
    }
    if (node.isNumber()) {
      String digits = node.asString();
      String scrubbed = MaskingPolicy.scrub(digits);
      return scrubbed.equals(digits) ? node : f.stringNode(scrubbed);
    }
    return node;
  }

  private static JsonNode field(String key, JsonNode value) {
    if (value.isNull()) {
      return value;
    }
    List<String> words = words(key);
    if (words.stream().anyMatch(w -> SECRET_WORDS.contains(w) || w.startsWith("passw"))) {
      return JsonNodeFactory.instance.stringNode(MaskingPolicy.mask(SensitiveType.SECRET, "x"));
    }
    if (!words.isEmpty() && words.getLast().equals("name") && value.isString()) {
      return JsonNodeFactory.instance.stringNode(MaskingPolicy.mask(SensitiveType.NAME, value.asString()));
    }
    return masked(value);
  }

  /** A key's words, split at camelCase and at anything but a letter ({@code holderName}: holder, name). */
  private static List<String> words(String key) {
    return Arrays.stream(key.split("(?<=[a-z])(?=[A-Z])|[^A-Za-z]+"))
        .filter(w -> !w.isEmpty())
        .map(w -> w.toLowerCase(Locale.ROOT))
        .toList();
  }
}
