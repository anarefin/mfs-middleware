package mfs.mw.simulators.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Security headers (spec 18.6): the strict policy everywhere; the Swagger UI (dev and test profiles
 * only, ticket 101) gets a policy that lets its own scripts, styles and icons load, still same-origin.
 */
class SecurityHeadersFilterTest {

  static MockHttpServletResponse get(String uri) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", uri), response, new MockFilterChain());
    return response;
  }

  @Test
  void apiResponsesGetTheStrictPolicy() throws Exception {
    MockHttpServletResponse r = get("/v3/api-docs");
    assertThat(r.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
    assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void theSwaggerUiMayLoadItsOwnAssetsOnly() throws Exception {
    MockHttpServletResponse r = get("/swagger-ui/index.html");
    assertThat(r.getHeader("Content-Security-Policy"))
        .isEqualTo(SecurityHeadersFilter.SWAGGER_UI_POLICY)
        .contains("default-src 'self'", "frame-ancestors 'none'")
        .doesNotContain("script-src 'unsafe-inline'", "*");
    assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
  }
}
