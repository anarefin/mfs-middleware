package mfs.mw.core.mock.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.core.mock.dto.ErrorEnvelope;
import mfs.mw.core.mock.dto.ReplyMode;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.service.mockcontrol.Decision;
import mfs.mw.core.mock.service.mockcontrol.RestCall;
import mfs.mw.core.mock.service.mockcontrol.ScenarioEngine;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mock control on the core wallet REST API {@code /core/v1/**} (ADR D33, ticket 103; registered by
 * {@code config/MockControlConfig} in the mock-control profile only, after the security chain). The
 * switch is checked once per call: DOWN closes the connection unanswered ({@link MockAbortValve}),
 * MAINTENANCE answers {@code 503 MW-SYS-CORE_UNAVAILABLE}, nothing applied in either; SLOW waits its
 * latency first. A read ({@code accounts}, {@code balance}, {@code charges}, {@code limits}, {@code
 * statements}, {@code query}) is decided here: a BUSINESS_ERROR answers {@code 422 MW-CORE-<code>}.
 * A money call is decided by the engine inside {@code CoreCommandService} (a BUSINESS_ERROR is the
 * command's stored refusal, so a repeat answers the same). Then the answer is shaped: DELAY waits,
 * TIMEOUT holds it (the call applied), HTTP_5XX replaces it with {@code <status> MW-SYS-CORE_ERROR},
 * MALFORMED_RESPONSE cuts it short, CONNECTION_RESET drops the connection.
 */
public class MockControlFilter extends OncePerRequestFilter {

  /** The cut-short body of a MALFORMED_RESPONSE. */
  public static final String CUT_SHORT = "{\"txnId\":\"";

  static final String CORE_UNAVAILABLE = "MW-SYS-CORE_UNAVAILABLE";
  static final String CORE_ERROR = "MW-SYS-CORE_ERROR";
  static final String ANSWER_LOST = "MW-TMO-CORE_ANSWER_LOST";

  private final ScenarioEngine engine;
  private final JsonMapper json;

  public MockControlFilter(ScenarioEngine engine, JsonMapper json) {
    this.engine = engine;
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/core/v1/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    RestCall call = RestCall.begin();
    try {
      Decision gate = engine.admit();
      pause(gate.delayMs());
      if (gate.gate() == Decision.Gate.DOWN) {
        request.setAttribute(MockAbortValve.ABORT_ATTRIBUTE, "core");
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        return;
      }
      if (gate.gate() == Decision.Gate.MAINTENANCE) {
        error(response, 503, CORE_UNAVAILABLE, "Core in maintenance (mock control)", true);
        return;
      }
      String read = readOperation(request);
      if (read != null) {
        Decision d = engine.decide(read, readKeys(request));
        if (d.refuses()) {
          error(response, 422, ErrorCodes.CORE_PREFIX + d.reason(), "Injected rejection", false);
          return;
        }
      }
      ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
      chain.doFilter(request, wrapper);
      shape(call.decision(), request, wrapper);
      wrapper.copyBodyToResponse();
    } finally {
      RestCall.end();
    }
  }

  private void shape(Decision d, HttpServletRequest request, ContentCachingResponseWrapper wrapper) throws IOException {
    if (d == null) {
      return;
    }
    pause(d.delayMs());
    switch (d.mode()) {
      case TIMEOUT -> {
        pause(d.holdMs());
        loseIfNoReply(d, wrapper);
      }
      case SERVER_ERROR_AFTER_SEND -> {
        wrapper.resetBuffer();
        error(wrapper, d.statusOr(500), CORE_ERROR, "Core error after the call was applied (mock control)", true);
      }
      case MALFORMED -> {
        wrapper.resetBuffer();
        wrapper.getOutputStream().write(CUT_SHORT.getBytes(StandardCharsets.UTF_8));
      }
      case CONNECTION_RESET -> request.setAttribute(MockAbortValve.ABORT_ATTRIBUTE, "core");
      default -> {
        // SUCCESS, BUSINESS_FAILURE (already the stored 422), PARTIAL_SUCCESS: the answer as it is,
        // unless the reply is lost
        loseIfNoReply(d, wrapper);
      }
    }
  }

  /**
   * NO_CALLBACK (the legacy DROP_REPLY fault, ticket 95b): the call was applied (or refused), but its
   * answer never reaches the caller, as its result event never does: {@code 504 MW-TMO-CORE_ANSWER_LOST},
   * an unknown outcome the caller settles by WAL-13.
   */
  private void loseIfNoReply(Decision d, ContentCachingResponseWrapper wrapper) throws IOException {
    if (d.reply() == ReplyMode.NONE) {
      wrapper.resetBuffer();
      error(wrapper, 504, ANSWER_LOST, "The core's answer was lost after the call was applied (mock control)", true);
    }
  }

  private void error(HttpServletResponse response, int status, String code, String message, boolean retryable)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ErrorEnvelope body = new ErrorEnvelope(code, message, null, null, MDC.get("correlationId"), retryable, List.of());
    response.getOutputStream().write(json.writeValueAsBytes(body));
  }

  /** The operation of a read path, null for a money call (decided with the command). */
  static String readOperation(HttpServletRequest request) {
    String path = request.getRequestURI().substring("/core/v1".length());
    String method = request.getMethod();
    if ("GET".equals(method)) {
      if (path.startsWith("/accounts/")) {
        return "accounts";
      }
      if (path.startsWith("/balance/")) {
        return "balance";
      }
      if (path.startsWith("/statements")) {
        return "statements";
      }
      if (path.startsWith("/transactions/")) {
        return "query";
      }
    }
    if ("POST".equals(method) && path.equals("/charges/calculate")) {
      return "charges";
    }
    if ("POST".equals(method) && path.equals("/limits/check")) {
      return "limits";
    }
    return null;
  }

  /** A read's scope keys: the wallet or txn of its path, the {@code walletId} query parameter. */
  private static Map<String, String> readKeys(HttpServletRequest request) {
    Map<String, String> keys = new LinkedHashMap<>();
    String path = request.getRequestURI();
    String last = path.substring(path.lastIndexOf('/') + 1);
    if (!last.isBlank()) {
      keys.put(path.contains("/transactions/") ? "txnId" : "wallet", last);
    }
    String walletId = request.getParameter("walletId");
    if (walletId != null) {
      keys.put("wallet", walletId);
    }
    return keys;
  }

  private static void pause(long ms) {
    if (ms <= 0) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
