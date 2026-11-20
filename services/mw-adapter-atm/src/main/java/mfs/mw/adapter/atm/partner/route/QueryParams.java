package mfs.mw.adapter.atm.partner.route;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one reader of an endpoint or Kamelet query string for the partner checks ({@link
 * PartnerHostGuard}, {@link PartnerBatchRoutes}): {@code name=value} pairs split on {@code &}, empty
 * pairs skipped, a pair without {@code =} an empty value, and name and value URL-decoded (UTF-8) as
 * Camel reads them, so a check always sees the value Camel will use. Duplicates are kept, in order:
 * the caller decides whether a repeat is allowed.
 */
final class QueryParams {

  private QueryParams() {}

  /**
   * @throws IllegalArgumentException when a name or value is not valid URL encoding
   */
  static List<Map.Entry<String, String>> parse(String query) {
    List<Map.Entry<String, String>> out = new ArrayList<>();
    if (query == null || query.isEmpty()) {
      return out;
    }
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      out.add(Map.entry(
          URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
          eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)));
    }
    return out;
  }
}
