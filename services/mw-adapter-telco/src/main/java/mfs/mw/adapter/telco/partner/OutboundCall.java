package mfs.mw.adapter.telco.partner;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP request about to leave for a partner. Mutable on purpose: the {@link AuthScheme} adds
 * its headers just before sending. The body is final, so a signature over it stays valid.
 */
public final class OutboundCall {

  private final String method;
  private final URI uri;
  private final byte[] body;
  private final Map<String, String> headers = new LinkedHashMap<>();

  public OutboundCall(String method, URI uri, byte[] body) {
    this.method = method;
    this.uri = uri;
    this.body = body == null ? new byte[0] : body.clone();
  }

  public String method() {
    return method;
  }

  public URI uri() {
    return uri;
  }

  /** Path and query as sent, e.g. {@code /ocs/v1/topup?x=1}. */
  public String pathAndQuery() {
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
  }

  public byte[] body() {
    return body.clone();
  }

  public void header(String name, String value) {
    headers.put(name, value);
  }

  public Map<String, String> headers() {
    return Map.copyOf(headers);
  }
}
