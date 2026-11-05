package mfs.mw.app.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Keeps the body of {@code /app/**} POSTs in memory ({@link CachedBodyRequest}) so the body
 * schema checks, the device signature hash (ticket 29) and the controller read the same bytes, the
 * ones the client sent. A body over {@link CachedBodyRequest#MAX_BODY} is answered 413
 * MW-VAL-BODY_TOO_LARGE with the 5.3 envelope (through the global handler, as the security entry
 * points do) and goes no further. Runs after Spring Security, so an unauthenticated request is 401
 * first. Registered by {@code config/BodySchemaConfig}.
 */
public class CachedBodyFilter extends OncePerRequestFilter {

  private final HandlerExceptionResolver errors;
  private final int limit;

  public CachedBodyFilter(HandlerExceptionResolver errors, int limit) {
    this.errors = errors;
    this.limit = limit;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/app/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    CachedBodyRequest cached;
    try {
      cached = new CachedBodyRequest(request, limit);
    } catch (MwException tooLarge) {
      errors.resolveException(request, response, null, tooLarge);
      return;
    }
    chain.doFilter(cached, response);
  }
}
