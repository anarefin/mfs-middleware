package mfs.mw.app.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import mfs.mw.app.gateway.dto.SignedRequest;
import mfs.mw.app.gateway.filter.CachedBodyRequest;
import mfs.mw.app.gateway.service.RequestSignatures;
import org.springframework.web.util.WebUtils;

/**
 * Reads the device signature headers (spec 9.1) and the body hash off an HTTP request.
 *
 * <p>The body hash is that of the bytes the client sent. APISIX's request-validation re-encodes a
 * validated JSON body before forwarding it (so the upstream parses exactly the JSON it checked),
 * which changes the bytes; the edge therefore hashes the client's bytes first and forwards the hash
 * as {@code X-Mw-Body-Sha256} (deploy/apisix/lua/app-body-sha256.lua). The edge strips every
 * client {@code X-Mw-*} header, so this one can only come from APISIX; without it (a direct call
 * inside the app network) the received body is hashed.
 */
public final class SignedRequests {

  public static final String DEVICE_ID = "X-Device-Id";
  public static final String TIMESTAMP = "X-Timestamp";
  public static final String NONCE = "X-Nonce";
  public static final String SIGNATURE = "X-Signature";
  public static final String EDGE_BODY_SHA256 = "X-Mw-Body-Sha256";

  private SignedRequests() {}

  public static SignedRequest from(HttpServletRequest request) {
    CachedBodyRequest cached = WebUtils.getNativeRequest(request, CachedBodyRequest.class);
    String bodySha256 = request.getHeader(EDGE_BODY_SHA256);
    if (bodySha256 == null) {
      bodySha256 = RequestSignatures.sha256Hex(cached == null ? new byte[0] : cached.body());
    }
    String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    return new SignedRequest(
        request.getHeader(DEVICE_ID),
        request.getMethod(),
        path,
        bodySha256,
        request.getHeader(TIMESTAMP),
        request.getHeader(NONCE),
        request.getHeader(SIGNATURE));
  }
}
