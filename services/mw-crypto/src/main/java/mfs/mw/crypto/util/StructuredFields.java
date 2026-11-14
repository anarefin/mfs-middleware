package mfs.mw.crypto.util;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The part of RFC 8941 structured field values that RFC 9421 signatures use (ticket 65): parsing a
 * dictionary ({@code Signature-Input}, {@code Signature}) whose members are items or inner lists with
 * parameters, and serialising inner lists, items and parameters. Bare items: string ({@link String}),
 * token ({@link Token}), integer ({@link Long}), boolean ({@link Boolean}), byte sequence ({@code
 * byte[]}); decimals are refused. Anything malformed is an {@link IllegalArgumentException}.
 */
public final class StructuredFields {

  /** An RFC 8941 token (serialised without quotes). */
  public record Token(String value) {}

  /** A bare item with its parameters (in order). */
  public record Item(Object value, Map<String, Object> params) {}

  /** An inner list with its parameters (in order). */
  public record InnerList(List<Item> items, Map<String, Object> params) {}

  private final String s;
  private int i;

  private StructuredFields(String s) {
    this.s = s;
  }

  /** The members of a dictionary field value, each an {@link Item} or an {@link InnerList}. */
  public static Map<String, Object> parseDictionary(String value) {
    StructuredFields p = new StructuredFields(value);
    Map<String, Object> out = new LinkedHashMap<>();
    p.sp();
    if (p.end()) {
      throw new IllegalArgumentException("empty dictionary");
    }
    while (true) {
      String key = p.key();
      Object member;
      if (p.peek('=')) {
        p.i++;
        member = p.peek('(') ? p.innerList() : p.item();
      } else {
        member = new Item(Boolean.TRUE, p.params());
      }
      out.put(key, member);
      p.ows();
      if (p.end()) {
        return out;
      }
      p.expect(',');
      p.ows();
      if (p.end()) {
        throw new IllegalArgumentException("trailing comma");
      }
    }
  }

  /** One item with its parameters, the whole value (e.g. a component identifier {@code "content-digest";sf}). */
  public static Item parseItem(String value) {
    StructuredFields p = new StructuredFields(value);
    Item item = p.item();
    if (!p.end()) {
      throw new IllegalArgumentException("trailing characters");
    }
    return item;
  }

  public static String serialize(InnerList list) {
    StringBuilder b = new StringBuilder("(");
    for (int k = 0; k < list.items().size(); k++) {
      if (k > 0) {
        b.append(' ');
      }
      b.append(serialize(list.items().get(k)));
    }
    return b.append(')').append(params(list.params())).toString();
  }

  public static String serialize(Item item) {
    return bare(item.value()) + params(item.params());
  }

  public static String params(Map<String, Object> params) {
    StringBuilder b = new StringBuilder();
    params.forEach((k, v) -> {
      b.append(';').append(k);
      if (!Boolean.TRUE.equals(v)) {
        b.append('=').append(bare(v));
      }
    });
    return b.toString();
  }

  public static String bare(Object v) {
    return switch (v) {
      case String str -> {
        StringBuilder b = new StringBuilder("\"");
        for (char c : str.toCharArray()) {
          if (c < 0x20 || c > 0x7e) {
            throw new IllegalArgumentException("not a string");
          }
          if (c == '"' || c == '\\') {
            b.append('\\');
          }
          b.append(c);
        }
        yield b.append('"').toString();
      }
      case Token t -> t.value();
      case Long l -> Long.toString(l);
      case Integer n -> Integer.toString(n);
      case Boolean bool -> bool ? "?1" : "?0";
      case byte[] bytes -> ":" + Base64.getEncoder().encodeToString(bytes) + ":";
      default -> throw new IllegalArgumentException("not a bare item");
    };
  }

  // --- parser (RFC 8941 section 4.2) ---

  private InnerList innerList() {
    expect('(');
    List<Item> items = new ArrayList<>();
    while (true) {
      sp();
      if (peek(')')) {
        i++;
        return new InnerList(items, params());
      }
      items.add(item());
      if (!peek(' ') && !peek(')')) {
        throw new IllegalArgumentException("bad inner list");
      }
    }
  }

  private Item item() {
    Object v = bareItem();
    return new Item(v, params());
  }

  private Map<String, Object> params() {
    Map<String, Object> out = new LinkedHashMap<>();
    while (peek(';')) {
      i++;
      sp();
      String key = key();
      Object v = Boolean.TRUE;
      if (peek('=')) {
        i++;
        v = bareItem();
      }
      out.put(key, v);
    }
    return out;
  }

  private String key() {
    int start = i;
    if (end() || !(lcalpha(s.charAt(i)) || s.charAt(i) == '*')) {
      throw new IllegalArgumentException("bad key");
    }
    while (!end() && (lcalpha(s.charAt(i)) || digit(s.charAt(i)) || "_-.*".indexOf(s.charAt(i)) >= 0)) {
      i++;
    }
    return s.substring(start, i);
  }

  private Object bareItem() {
    if (end()) {
      throw new IllegalArgumentException("missing item");
    }
    char c = s.charAt(i);
    if (c == '"') {
      return string();
    }
    if (c == ':') {
      return bytes();
    }
    if (c == '?') {
      i++;
      if (peek('1') || peek('0')) {
        return s.charAt(i++) == '1';
      }
      throw new IllegalArgumentException("bad boolean");
    }
    if (c == '-' || digit(c)) {
      return integer();
    }
    if (Character.isLetter(c) && c < 0x80 || c == '*') {
      return token();
    }
    throw new IllegalArgumentException("bad item");
  }

  private String string() {
    expect('"');
    StringBuilder b = new StringBuilder();
    while (!end()) {
      char c = s.charAt(i++);
      if (c == '\\') {
        if (end() || (s.charAt(i) != '"' && s.charAt(i) != '\\')) {
          throw new IllegalArgumentException("bad escape");
        }
        b.append(s.charAt(i++));
      } else if (c == '"') {
        return b.toString();
      } else if (c < 0x20 || c > 0x7e) {
        throw new IllegalArgumentException("bad string");
      } else {
        b.append(c);
      }
    }
    throw new IllegalArgumentException("open string");
  }

  private byte[] bytes() {
    expect(':');
    int close = s.indexOf(':', i);
    if (close < 0) {
      throw new IllegalArgumentException("open byte sequence");
    }
    String b64 = s.substring(i, close);
    if (!b64.matches("[A-Za-z0-9+/]*={0,2}")) {
      throw new IllegalArgumentException("bad byte sequence");
    }
    i = close + 1;
    return Base64.getDecoder().decode(b64);
  }

  private Long integer() {
    int start = i;
    if (peek('-')) {
      i++;
    }
    int digits = i;
    while (!end() && digit(s.charAt(i))) {
      i++;
    }
    if (i == digits || i - digits > 15 || peek('.')) {
      throw new IllegalArgumentException("bad integer");
    }
    return Long.parseLong(s.substring(start, i));
  }

  private Token token() {
    int start = i++;
    while (!end() && (s.charAt(i) > 0x20 && s.charAt(i) < 0x7f && "\"(),;<=>?@[\\]{} ".indexOf(s.charAt(i)) < 0
        || s.charAt(i) == ':' || s.charAt(i) == '/')) {
      i++;
    }
    return new Token(s.substring(start, i));
  }

  private boolean end() {
    return i >= s.length();
  }

  private boolean peek(char c) {
    return !end() && s.charAt(i) == c;
  }

  private void expect(char c) {
    if (!peek(c)) {
      throw new IllegalArgumentException("expected " + c);
    }
    i++;
  }

  private void sp() {
    while (peek(' ')) {
      i++;
    }
  }

  private void ows() {
    while (peek(' ') || peek('\t')) {
      i++;
    }
  }

  private static boolean lcalpha(char c) {
    return c >= 'a' && c <= 'z';
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }
}
