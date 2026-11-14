package mfs.mw.crypto.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The dev-only test connector and the mTLS internal API stay apart (ticket 76). */
class TestApiPortFilterTest {

  static int run(boolean onTestPort, String path) throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    new TestApiPortFilter(r -> onTestPort).doFilter(req, res, chain);
    return chain.getRequest() == null ? res.getStatus() : 0;
  }

  @Test
  void theTestConnectorServesOnlyThePayShieldTestApi() throws Exception {
    assertThat(run(true, "/test/v1/payshield/keys/TPK")).isZero();
    assertThat(run(true, "/internal/v1/keys")).isEqualTo(404);
    assertThat(run(true, "/internal/v1/pin/verify")).isEqualTo(404);
    assertThat(run(true, "/actuator/health")).isEqualTo(404);
    assertThat(run(true, "/test/v1/other")).isEqualTo(404);
  }

  @Test
  void theInternalPortNeverServesTheTestApi() throws Exception {
    assertThat(run(false, "/test/v1/payshield/keys/TPK")).isEqualTo(404);
    assertThat(run(false, "/internal/v1/keys")).isZero();
  }
}
