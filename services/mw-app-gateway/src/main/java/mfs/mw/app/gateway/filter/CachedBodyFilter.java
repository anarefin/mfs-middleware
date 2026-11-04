package mfs.mw.app.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the body of {@code /app/**} POSTs in memory ({@link CachedBodyRequest}) so the device
 * signature check (ticket 29) hashes the bytes the client signed. Registered by {@code
 * config/DeviceBindingConfig} only when device binding is on.
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
