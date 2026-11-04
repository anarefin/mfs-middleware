package mfs.mw.audit.dw.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** X-Correlation-Id: propagated, generated if absent, in MDC for the request only (2.2, 21.3). */
class CorrelationIdFilterTest {

  final CorrelationIdFilter filter = new CorrelationIdFilter();
  final AtomicReference<String> seenInMdc = new AtomicReference<>();
  final AtomicReference<Object> seenAttribute = new AtomicReference<>();

  MockHttpServletResponse run(MockHttpServletRequest req) throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(
        req,
        res,
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {},
            (rq, rs, chain) -> {
              seenInMdc.set(MDC.get("correlationId"));
              seenAttribute.set(((HttpServletRequest) rq).getAttribute("correlationId"));
            }));
    return res;
  }

  @Test
  void incomingIdIsKeptEchoedAndInMdc() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader("X-Correlation-Id", "abc-123");
    MockHttpServletResponse res = run(req);
    assertThat(seenInMdc.get()).isEqualTo("abc-123");
    assertThat(seenAttribute.get()).isEqualTo("abc-123");
    assertThat(res.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
  }

  @Test
  void missingIdIsGenerated() throws Exception {
    MockHttpServletResponse res = run(new MockHttpServletRequest("GET", "/x"));
    assertThat(seenInMdc.get()).matches("[0-9a-f-]{36}");
    assertThat(res.getHeader("X-Correlation-Id")).isEqualTo(seenInMdc.get());
  }

  @Test
  void unsafeOrOversizedIdIsReplaced() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader("X-Correlation-Id", "bad\nid\"{}");
    run(req);
    assertThat(seenInMdc.get()).matches("[0-9a-f-]{36}");

    MockHttpServletRequest big = new MockHttpServletRequest("GET", "/x");
    big.addHeader("X-Correlation-Id", "a".repeat(129));
    run(big);
    assertThat(seenInMdc.get()).matches("[0-9a-f-]{36}");
  }

  @Test
  void mdcIsClearedAfterTheRequest() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader("X-Correlation-Id", "abc-123");
    run(req);
    assertThat(MDC.get("correlationId")).isNull();
  }
}
