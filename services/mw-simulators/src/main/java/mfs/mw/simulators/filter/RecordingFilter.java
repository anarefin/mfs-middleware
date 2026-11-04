package mfs.mw.simulators.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mfs.mw.simulators.service.RequestRecorder;
import mfs.mw.simulators.service.ScenarioStore;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Records every request to a simulator's own endpoints ({@code /<partner>/...}), whatever the reply. */
public class RecordingFilter extends OncePerRequestFilter {

  private static final Set<String> SKIPPED_HEADERS = Set.of("authorization", "cookie");

  private final ScenarioStore scenarios;
  private final RequestRecorder recorder;
  private final Clock clock;

  public RecordingFilter(ScenarioStore scenarios, RequestRecorder recorder, Clock clock) {
    this.scenarios = scenarios;
    this.recorder = recorder;
    this.clock = clock;
  }

  private static String partnerOf(String path) {
    int end = path.indexOf('/', 1);
    return path.length() > 1 && end > 0 ? path.substring(1, end) : "";
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !scenarios.isPartner(partnerOf(request.getRequestURI()));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(request, 1 << 20);
    try {
      chain.doFilter(wrapper, response);
    } finally {
      Map<String, String> headers = new LinkedHashMap<>();
      for (String name : Collections.list(request.getHeaderNames())) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!SKIPPED_HEADERS.contains(lower)) {
          headers.put(lower, request.getHeader(name));
        }
      }
      recorder.record(
          partnerOf(request.getRequestURI()),
          request.getMethod(),
          request.getRequestURI(),
          request.getQueryString(),
          headers,
          new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8),
          clock.instant());
    }
  }
}
