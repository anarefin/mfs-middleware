package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * A versioned USSD menu tree (spec 9.2), stored as JSON in {@code ussd.menu}. Menus are data, not
 * code; a running session keeps the version it started on.
 *
 * @param messages engine texts: {@code invalid}, {@code tooManyAttempts}, {@code expired},
 *     {@code processing}, {@code unavailable}, {@code more}; optional (ticket 72) {@code wrongPin},
 *     {@code pinLocked}, {@code pinBlocked}, {@code walletLocked}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Menu(
    String menuId, int version, String start, Map<String, Text> messages, Map<String, MenuNode> nodes) {

  public static final String INVALID = "invalid";
  public static final String TOO_MANY_ATTEMPTS = "tooManyAttempts";
  public static final String EXPIRED = "expired";
  public static final String PROCESSING = "processing";
  public static final String UNAVAILABLE = "unavailable";
  public static final String MORE = "more";
  /** mw-auth said wrong PIN (ticket 72); {@code {triesLeft}} is filled in. Optional: built-in default. */
  public static final String WRONG_PIN = "wrongPin";
  /** The PIN is locked for a while (MW-AUTH-041). Optional. */
  public static final String PIN_LOCKED = "pinLocked";
  /** The PIN is locked until a reset (MW-AUTH-042). Optional. */
  public static final String PIN_BLOCKED = "pinBlocked";
  /** The wallet is locked or not active (MW-AUTH-043). Optional. */
  public static final String WALLET_LOCKED = "walletLocked";

  public Menu {
    messages = messages == null ? Map.of() : Map.copyOf(messages);
    nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
  }

  public MenuNode node(String id) {
    MenuNode n = nodes.get(id);
    if (n == null) {
      throw new IllegalStateException("menu " + menuId + " v" + version + " has no node " + id);
    }
    return n;
  }

  public String message(String key, String language) {
    Text t = messages.get(key);
    return t == null ? "" : t.in(language);
  }
}
