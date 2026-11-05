package mfs.mw.core.mock.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * X-Correlation-Id: propagated, generated if absent or unsafe (bad characters, or a run of 6+
 * digits that could hide an NID or MSISDN), in MDC for the request only (2.2, 21.3). A caller
 * traceparent whose trace or parent id has such a run is dropped before tracing reads it.
 */
class CorrelationIdFilterTest {

  final CorrelationIdFilter filter = new CorrelationIdFilter();
  final AtomicReference<String> seenInMdc = new AtomicReference<>();
  final AtomicReference<Object> seenAttribute = new AtomicReference<>();
  final AtomicReference<HttpServletRequest> seenRequest = new AtomicReference<>();
  final Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
  final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

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
              seenRequest.set((HttpServletRequest) rq);
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
  void idWithARunOfSixDigitsIsReplacedAndWarnedWithoutTheValue() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader("X-Correlation-Id", "corr-2345678901234");
    MockHttpServletResponse res = run(req);
    assertThat(seenInMdc.get())
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    assertThat(res.getHeader("X-Correlation-Id")).isEqualTo(seenInMdc.get());
    assertThat(logs.list)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getLevel().toString()).isEqualTo("WARN");
              assertThat(e.getKeyValuePairs())
                  .anySatisfy(
                      kv -> {
                        assertThat(kv.key).isEqualTo("event");
                        assertThat(kv.value).isEqualTo("correlation.id.replaced");
                      });
              assertThat(e.getFormattedMessage()).doesNotContain("2345678901234");
              assertThat(String.valueOf(e.getKeyValuePairs())).doesNotContain("2345678901234");
            });

    MockHttpServletRequest six = new MockHttpServletRequest("GET", "/x");
    six.addHeader("X-Correlation-Id", "c-123456-x");
    run(six);
    assertThat(seenInMdc.get()).matches("[0-9a-f-]{36}");
  }

  @Test
  void idWithShorterDigitRunsIsKept() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader("X-Correlation-Id", "cid-12345-67890");
    run(req);
    assertThat(seenInMdc.get()).isEqualTo("cid-12345-67890");
    assertThat(logs.list).isEmpty();
  }

  @Test
  void traceparentWithARunOfSixDigitsIsDroppedBeforeTracing() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader("traceparent", "00-4bf92f3577b34da6a3ce901712345789-00f067aa0ba902b7-01");
    req.addHeader("tracestate", "k=v");
    run(req);
    assertThat(seenRequest.get().getHeader("traceparent")).isNull();
    assertThat(seenRequest.get().getHeader("tracestate")).isNull();
    assertThat(Collections.list(seenRequest.get().getHeaderNames()))
        .noneMatch(n -> n.equalsIgnoreCase("traceparent") || n.equalsIgnoreCase("tracestate"));
    assertThat(Collections.list(seenRequest.get().getHeaders("traceparent"))).isEmpty();

    MockHttpServletRequest span = new MockHttpServletRequest("GET", "/x");
    span.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-a0123456aa0ba902-01");
    run(span);
    assertThat(seenRequest.get().getHeader("traceparent")).isNull();
  }

  @Test
  void ordinaryTraceparentIsPassedOn() throws Exception {
    String tp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader("traceparent", tp);
    run(req);
    assertThat(seenRequest.get().getHeader("traceparent")).isEqualTo(tp);
  }

  @Test
  void mdcIsClearedAfterTheRequest() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    req.addHeader("X-Correlation-Id", "abc-123");
    run(req);
    assertThat(MDC.get("correlationId")).isNull();
  }
}
