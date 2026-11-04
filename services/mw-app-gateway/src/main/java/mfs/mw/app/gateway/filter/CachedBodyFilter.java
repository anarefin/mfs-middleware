package mfs.mw.app.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the body of {@code /app/**} POSTs in memory ({@link CachedBodyRequest}) so the submit
 * schema check, the device signature hash (ticket 29) and the controller read the same bytes, the
 * ones the client sent. Registered by {@code config/SubmitSchemaConfig}.
 */
public class CachedBodyFilter extends OncePerRequestFilter {

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/app/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(new CachedBodyRequest(request), response);
  }
}
