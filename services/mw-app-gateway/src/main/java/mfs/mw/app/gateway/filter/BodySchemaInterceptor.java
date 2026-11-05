package mfs.mw.app.gateway.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mfs.mw.app.gateway.service.BodySchema;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.WebUtils;

/**
 * Checks the body of one {@code POST /app/v1/...} endpoint against its {@link BodySchema} before the
 * device gate and the handler, on the bytes {@link CachedBodyFilter} kept: the same bytes the
 * device signature hashes and the controller parses. With {@code optional} an empty body (no bytes
 * at all) passes, as the contract's {@code requestBody.required: false} allows. A failure is an
 * {@code MwException}, answered 400 MW-VAL-001 by the global handler.
 */
public class BodySchemaInterceptor implements HandlerInterceptor {

  private final BodySchema schema;
  private final boolean optional;

  public BodySchemaInterceptor(BodySchema schema, boolean optional) {
    this.schema = schema;
    this.optional = optional;
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
    byte[] body = cached.body();
    if (optional && body.length == 0) {
      return true;
    }
    schema.check(body);
    return true;
  }
}
