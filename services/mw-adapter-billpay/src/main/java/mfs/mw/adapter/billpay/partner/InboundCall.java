package mfs.mw.adapter.billpay.partner;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A partner-initiated HTTP request (callback) as received. Header names are case-insensitive.
 *
 * @param partnerId partner named by the callback URL
 * @param pathAndQuery path and query as received, e.g. {@code /partner/gp/callback}
 */
public record InboundCall(
    String partnerId, String method, String pathAndQuery, Map<String, String> headers, byte[] body) {

  public InboundCall {
    TreeMap<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (headers != null) {
      h.putAll(headers);
    }
    headers = java.util.Collections.unmodifiableMap(h);
    body = body == null ? new byte[0] : body.clone();
  }

  public String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof InboundCall other
        && java.util.Objects.equals(partnerId, other.partnerId)
        && java.util.Objects.equals(method, other.method)
        && java.util.Objects.equals(pathAndQuery, other.pathAndQuery)
        && headers.equals(other.headers)
        && java.util.Arrays.equals(body, other.body);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(partnerId, method, pathAndQuery, headers, java.util.Arrays.hashCode(body));
  }

  @Override
  public String toString() {
    return "InboundCall[partnerId=" + partnerId + ", method=" + method + ", path=" + pathAndQuery + "]";
  }
}
