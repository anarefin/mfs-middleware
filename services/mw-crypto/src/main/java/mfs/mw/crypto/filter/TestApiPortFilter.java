package mfs.mw.crypto.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Predicate;
import org.apache.catalina.connector.Connector;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the dev-only test connector (ticket 76) and the mTLS internal API apart: {@code /test/**} is
 * answered only on the test connector, and the test connector answers nothing but {@code
 * /test/v1/payshield/**}; anything else is a bare 404 (no hint that the other port exists). Only
 * registered with {@code mw.crypto.test-api.enabled=true} ({@code config/TestApiConfig}).
 */
public class TestApiPortFilter extends OncePerRequestFilter {

  static final String TEST_PREFIX = "/test/";
  static final String ALLOWED = "/test/v1/payshield/";

  private final Predicate<HttpServletRequest> onTestConnector;

  public TestApiPortFilter(Predicate<HttpServletRequest> onTestConnector) {
    this.onTestConnector = onTestConnector;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean test = onTestConnector.test(request);
    if (test ? !path.startsWith(ALLOWED) : path.startsWith(TEST_PREFIX)) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    chain.doFilter(request, response);
  }

  /** The test connector; its local port is known once Tomcat started. */
  public record TestConnector(Connector connector) {

    /** Whether the request arrived on the test connector. */
    public boolean received(HttpServletRequest request) {
      int local = connector.getLocalPort();
      return local > 0 && request.getLocalPort() == local;
    }
  }
}
