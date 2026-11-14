package mfs.mw.admin.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.config.AdminApiProperties;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Both internal query clients authenticate as Keycloak client {@code mw-admin-api} of realm {@code
 * services} (client credentials, M3 gate decision): one token, fetched once, cached until shortly
 * before it expires, sent as a bearer token to mw-orchestrator and mw-audit-dw, and forgotten when
 * either answers 401. Anything but a usable answer is {@link QueryUnavailableException}.
 */
class InternalClientAuthTest {

  static final String SECRET = "test-only-client-secret-not-real";
  static final UUID TXN = UUID.fromString("019a5b2e-1c00-7abc-8def-000000000002");

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T08:00:00Z"));
  final AtomicInteger tokensIssued = new AtomicInteger();
  final List<String> tokenRequests = new CopyOnWriteArrayList<>();
  final List<String> tokenAuth = new CopyOnWriteArrayList<>();
  final List<String> calls = new CopyOnWriteArrayList<>();
  volatile int tokenStatus = 200;
  volatile int queryStatus = 200;
  volatile boolean rejectNextQuery;

  HttpServer server;
  OrchestratorQueryClient orchestrator;
  AuditQueryClient audit;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/realms/services/protocol/openid-connect/token", this::token);
    server.createContext("/internal/v1/transactions", ex -> query(ex, "{\"transaction\":{\"txnId\":\"" + TXN + "\"},\"timeline\":[],\"partnerCalls\":[]}"));
    server.createContext("/internal/v1/audit/events", ex -> query(ex, "{\"items\":[],\"limit\":500,\"offset\":0}"));
    server.start();
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    AdminApiProperties props =
        new AdminApiProperties(
            new AdminApiProperties.Jwt("http://k/certs", "/realms/ops"),
            new AdminApiProperties.InternalAuth(
                base.resolve("/realms/services/protocol/openid-connect/token"), "mw-admin-api", SECRET),
            new AdminApiProperties.Upstream(base),
            new AdminApiProperties.Upstream(URI.create(base + "/")),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            500);
    JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    InternalApiCaller caller = new InternalApiCaller(props, json, new ServiceTokenClient(props, json, clock));
    orchestrator = new OrchestratorQueryClient(props, caller);
    audit = new AuditQueryClient(props, caller);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  void token(HttpExchange ex) throws IOException {
    tokenRequests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    tokenAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
    if (tokenStatus != 200) {
      respond(ex, tokenStatus, "{\"error\":\"unauthorized_client\"}");
      return;
    }
    respond(ex, 200, "{\"access_token\":\"tok-" + tokensIssued.incrementAndGet() + "\",\"expires_in\":300}");
  }

  void query(HttpExchange ex, String body) throws IOException {
    String q = ex.getRequestURI().getRawQuery();
    calls.add(ex.getRequestURI().getPath() + (q == null ? "" : "?" + q) + " " + ex.getRequestHeaders().getFirst("Authorization"));
    if (rejectNextQuery) {
      rejectNextQuery = false;
      respond(ex, 401, "{\"code\":\"MW-AUTH-001\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
    } else if (queryStatus != 200) {
      respond(ex, queryStatus, "{\"code\":\"MW-VAL-003\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
    } else if (ex.getRequestURI().getPath().equals("/internal/v1/transactions")) {
      respond(ex, 200, "{\"items\":[],\"limit\":50,\"offset\":0}");
    } else {
      respond(ex, 200, body);
    }
  }

  static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @Test
  void oneClientCredentialsTokenServesBothApisAndIsReused() {
    Map<String, Object> filters = new java.util.LinkedHashMap<>();
    filters.put("msisdn", "+8801711000001");
    filters.put("status", null);
    filters.put("limit", 50);
    orchestrator.search(filters);
    assertThat(orchestrator.detail(TXN, true)).isPresent();
    assertThat(audit.ofTransaction(TXN, 500).items()).isEmpty();

    assertThat(tokensIssued).hasValue(1);
    assertThat(tokenRequests.get(0)).isEqualTo("grant_type=client_credentials");
    assertThat(tokenAuth.get(0))
        .isEqualTo("Basic " + Base64.getEncoder().encodeToString(("mw-admin-api:" + SECRET).getBytes(StandardCharsets.UTF_8)));
    assertThat(calls)
        .containsExactly(
            "/internal/v1/transactions?msisdn=%2B8801711000001&limit=50 Bearer tok-1",
            "/internal/v1/transactions/" + TXN + "?reveal=true Bearer tok-1",
            "/internal/v1/audit/events?txnId=" + TXN + "&limit=500 Bearer tok-1");
  }

  @Test
  void aMaskedDetailDoesNotAskForAReveal() {
    orchestrator.detail(TXN, false);
    assertThat(calls).containsExactly("/internal/v1/transactions/" + TXN + " Bearer tok-1");
  }

  @Test
  void aNewTokenIsFetchedShortlyBeforeTheOldOneExpires() {
    orchestrator.search(Map.of());
    clock.advance(Duration.ofSeconds(250));
    audit.ofTransaction(TXN, 10);
    clock.advance(Duration.ofSeconds(30));
    orchestrator.search(Map.of());

    assertThat(calls).extracting(c -> c.substring(c.lastIndexOf(' ') + 1)).containsExactly("tok-1", "tok-1", "tok-2");
  }

  @Test
  void aRefusedTokenIsForgottenAndTheCallIsRetryable() {
    orchestrator.search(Map.of());
    rejectNextQuery = true;
    assertThatThrownBy(() -> audit.ofTransaction(TXN, 10)).isInstanceOf(QueryUnavailableException.class);
    orchestrator.search(Map.of());

    assertThat(tokensIssued).hasValue(2);
  }

  @Test
  void notFoundIsEmptyAndOtherAnswersAreUnavailable() {
    queryStatus = 404;
    assertThat(orchestrator.detail(TXN, false)).isEmpty();
    queryStatus = 500;
    assertThatThrownBy(() -> orchestrator.detail(TXN, false)).isInstanceOf(QueryUnavailableException.class);
    assertThatThrownBy(() -> orchestrator.search(Map.of())).isInstanceOf(QueryUnavailableException.class);
    assertThatThrownBy(() -> audit.ofTransaction(TXN, 10)).isInstanceOf(QueryUnavailableException.class);
  }

  @Test
  void noTokenIsUnavailableAndTheSecretIsNeverShown() {
    tokenStatus = 401;
    assertThatThrownBy(() -> orchestrator.search(Map.of()))
        .isInstanceOf(QueryUnavailableException.class)
        .hasMessageNotContaining(SECRET);
    assertThat(calls).isEmpty();
    assertThat(new AdminApiProperties.InternalAuth(URI.create("http://k/t"), "mw-admin-api", SECRET).toString())
        .doesNotContain(SECRET)
        .contains("mw-admin-api");
  }
}
