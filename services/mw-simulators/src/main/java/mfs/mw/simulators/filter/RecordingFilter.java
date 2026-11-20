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
import mfs.mw.simulators.dto.BankProfile;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import mfs.mw.simulators.util.SoapXml;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Records every request to a mock party's own endpoints ({@code /<party>/...}, {@link MockParties}) in the
 * mock-control {@link Journal}, whatever the reply. The
 * text of a WS-Security {@code Password} element (the billagg SOAP front end) is recorded as {@code ***}.
 */
public class RecordingFilter extends OncePerRequestFilter {

  private static final String BANK = "bank";
  private static final Set<String> SKIPPED_HEADERS = Set.of("authorization", "cookie");

  private final Journal journal;
  private final Clock clock;

  public RecordingFilter(Journal journal, Clock clock) {
    this.journal = journal;
    this.clock = clock;
  }

  /** The party of a wire path: its first segment, or {@code bank-<code>} for the bank gateway's {@code /bank/<code>/...}. */
  static String partnerOf(String path) {
    int end = path.indexOf('/', 1);
    String first = path.length() > 1 && end > 0 ? path.substring(1, end) : "";
    if (BANK.equals(first)) {
      int next = path.indexOf('/', end + 1);
      return next > end + 1 ? BankProfile.PARTY_PREFIX + path.substring(end + 1, next) : "";
    }
    return first;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !MockParties.isParty(partnerOf(request.getRequestURI()));
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
      journal.record(
          partnerOf(request.getRequestURI()),
          request.getMethod(),
          request.getRequestURI(),
          request.getQueryString(),
          headers,
          SoapXml.redactPasswords(new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8)),
          clock.instant());
    }
  }
}
