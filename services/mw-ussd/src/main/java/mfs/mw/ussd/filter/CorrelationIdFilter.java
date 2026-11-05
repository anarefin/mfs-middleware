package mfs.mw.ussd.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code X-Correlation-Id} (or generates one when absent or unsafe), puts it in the MDC
 * and as a request attribute for the whole request, and echoes it on the response (spec 2.2,
 * 5.1, 21.3). Runs first so every log line of the request carries it.
 *
 * <p>Correlation and trace ids are logged unmasked (they are traced by exact match), so a caller
 * value that could hide an NID, account number or MSISDN is not let in: a caller id with a run of
 * 6 or more digits is replaced by a generated UUID like an unsafe one (the APISIX edge applies the
 * same rule), and a {@code traceparent} whose trace id or parent id holds such a run is dropped
 * with its {@code tracestate}, so tracing starts a new trace. This filter runs before the server
 * observation filter, which is what reads {@code traceparent}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

  /** Printable, log-safe ids only; anything else is replaced, never trusted. */
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  /** A run of 6 or more digits: could be (part of) an NID, account number or MSISDN. */
  private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String id;
    if (incoming == null || !SAFE.matcher(incoming).matches()) {
      id = UUID.randomUUID().toString();
    } else if (SIX_DIGITS.matcher(incoming).find()) {
      id = UUID.randomUUID().toString();
      log.atWarn()
          .addKeyValue("event", "correlation.id.replaced")
          .log("caller correlation id holds a run of 6+ digits; replaced by a generated one");
    } else {
      id = incoming;
    }
    HttpServletRequest forwarded = request;
    if (unsafeTraceparent(request.getHeader("traceparent"))) {
      forwarded = new WithoutTraceContext(request);
      log.atWarn()
          .addKeyValue("event", "traceparent.dropped")
          .log("caller traceparent holds a run of 6+ digits; dropped, a new trace is started");
    }
    forwarded.setAttribute(MDC_KEY, id);
    response.setHeader(HEADER, id);
    MDC.put(MDC_KEY, id);
    try {
      chain.doFilter(forwarded, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /**
   * Whether the trace id or parent id of a W3C {@code traceparent} ({@code
   * version-traceid-parentid-flags}) holds a run of 6+ digits. Anything that does not parse is
   * ignored by tracing anyway and is left alone.
   */
  private static boolean unsafeTraceparent(String traceparent) {
    if (traceparent == null) {
      return false;
    }
    String[] parts = traceparent.strip().split("-");
    return parts.length >= 3
        && (SIX_DIGITS.matcher(parts[1]).find() || SIX_DIGITS.matcher(parts[2]).find());
  }

  /** The request without its {@code traceparent} and {@code tracestate} headers. */
  private static final class WithoutTraceContext extends HttpServletRequestWrapper {

    WithoutTraceContext(HttpServletRequest request) {
      super(request);
    }

    private static boolean hidden(String name) {
      String n = name.toLowerCase(Locale.ROOT);
      return n.equals("traceparent") || n.equals("tracestate");
    }

    @Override
    public String getHeader(String name) {
      return hidden(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      List<String> names = Collections.list(super.getHeaderNames());
      names.removeIf(WithoutTraceContext::hidden);
      return Collections.enumeration(names);
    }
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }
}
