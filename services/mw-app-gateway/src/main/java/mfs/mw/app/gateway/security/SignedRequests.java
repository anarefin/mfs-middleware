package mfs.mw.app.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import mfs.mw.app.gateway.dto.SignedRequest;
import mfs.mw.app.gateway.filter.CachedBodyRequest;
import mfs.mw.app.gateway.service.RequestSignatures;
import org.springframework.web.util.WebUtils;

/** Reads the device signature headers (spec 9.1) and the body hash off an HTTP request. */
public final class SignedRequests {

  public static final String DEVICE_ID = "X-Device-Id";
  public static final String TIMESTAMP = "X-Timestamp";
  public static final String NONCE = "X-Nonce";
  public static final String SIGNATURE = "X-Signature";

  private SignedRequests() {}

  public static SignedRequest from(HttpServletRequest request) {
    CachedBodyRequest cached = WebUtils.getNativeRequest(request, CachedBodyRequest.class);
    byte[] body = cached == null ? new byte[0] : cached.body();
    String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    return new SignedRequest(
        request.getHeader(DEVICE_ID),
        request.getMethod(),
        path,
        RequestSignatures.sha256Hex(body),
        request.getHeader(TIMESTAMP),
        request.getHeader(NONCE),
        request.getHeader(SIGNATURE));
  }
}
