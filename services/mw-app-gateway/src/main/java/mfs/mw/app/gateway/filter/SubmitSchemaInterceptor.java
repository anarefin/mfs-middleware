package mfs.mw.app.gateway.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mfs.mw.app.gateway.service.SubmitBodySchema;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

/**
 * Checks the body of {@code POST /app/v1/transactions} against the submit schema
 * ({@link SubmitBodySchema}) before the device gate and the handler, on the bytes {@link
 * CachedBodyFilter} kept: the same bytes the device signature hashes and the controller parses.
 * A failure is an {@code MwException}, answered 400 MW-VAL-001 by the global handler.
 */
public class SubmitSchemaInterceptor implements HandlerInterceptor {

  private final SubmitBodySchema schema;

  public SubmitSchemaInterceptor(SubmitBodySchema schema) {
    this.schema = schema;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!"POST".equals(request.getMethod())) {
      return true;
    }
    CachedBodyRequest cached = WebUtils.getNativeRequest(request, CachedBodyRequest.class);
    if (cached == null) {
      throw new IllegalStateException("POST /app/** body not cached: CachedBodyFilter is not registered");
    }
    schema.check(cached.body());
    return true;
  }
}
