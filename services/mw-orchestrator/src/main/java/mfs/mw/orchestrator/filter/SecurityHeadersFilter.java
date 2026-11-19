package mfs.mw.orchestrator.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security headers on every HTTP response (spec 18.6). The Swagger UI pages ({@code /swagger-ui/},
 * served only in the dev and test profiles, ticket 101) get {@link #SWAGGER_UI_POLICY} instead of
 * {@code default-src 'none'}, which would stop the UI loading its own scripts and styles.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

  /** Same-origin assets only; inline styles and data: icons, as the Swagger UI bundle uses them. */
  public static final String SWAGGER_UI_POLICY =
      "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader(
        "Content-Security-Policy",
        request.getRequestURI().startsWith("/swagger-ui/")
            ? SWAGGER_UI_POLICY
            : "default-src 'none'; frame-ancestors 'none'");
    chain.doFilter(request, response);
  }
}
