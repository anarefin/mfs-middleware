package mfs.mw.portal.api.client;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.portal.api.MutableClock;
import mfs.mw.portal.api.config.OrchestratorClientProperties;
import mfs.mw.portal.api.exception.OrchestratorUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The internal query client authenticates as Keycloak client {@code mw-portal-api} of realm
 * {@code services} (client credentials, M3 gate decision): it fetches a token, caches it until
 * shortly before it expires, sends it as a bearer token and forgets it when the orchestrator
 * answers 401.
 */
class OrchestratorClientAuthTest {

  static final String SECRET = "test-only-client-secret-not-real";

  final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T08:00:00Z"));
  final AtomicInteger tokensIssued = new AtomicInteger();
  final List<String> tokenRequests = new CopyOnWriteArrayList<>();
  final List<String> tokenAuth = new CopyOnWriteArrayList<>();
  final List<String> bearers = new CopyOnWriteArrayList<>();
  volatile int tokenStatus = 200;
  volatile boolean rejectNextQuery;

  HttpServer server;
  OrchestratorQueryClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/realms/services/protocol/openid-connect/token", this::token);
    server.createContext("/internal/v1/transactions", this::query);
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    OrchestratorClientProperties props =
        new OrchestratorClientProperties(
            URI.create(base),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            new OrchestratorClientProperties.Auth(
                URI.create(base + "/realms/services/protocol/openid-connect/token"), "mw-portal-api", SECRET));
    JsonMapper json = JsonMapper.builder().build();
    client = new OrchestratorQueryClient(props, json, new ServiceTokenClient(props, json, clock));
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
    respond(ex, 200, "{\"access_token\":\"tok-" + tokensIssued.incrementAndGet() + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
  }

  void query(HttpExchange ex) throws IOException {
    bearers.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
    if (rejectNextQuery) {
      rejectNextQuery = false;
      respond(ex, 401, "{\"code\":\"MW-AUTH-001\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
      return;
    }
    respond(ex, 200, "{\"items\":[],\"limit\":50,\"offset\":0}");
  }

  static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @Test
  void fetchesOneClientCredentialsTokenAndReusesIt() {
    client.search("ORG001", Map.of());
    client.search("ORG001", Map.of());

    assertThat(tokensIssued).hasValue(1);
    assertThat(tokenRequests.get(0)).isEqualTo("grant_type=client_credentials");
    assertThat(tokenAuth.get(0))
        .isEqualTo(
            "Basic "
                + Base64.getEncoder().encodeToString(("mw-portal-api:" + SECRET).getBytes(StandardCharsets.UTF_8)));
    assertThat(bearers).containsExactly("Bearer tok-1", "Bearer tok-1");
  }

  @Test
  void fetchesANewTokenShortlyBeforeTheOldOneExpires() {
    client.search("ORG001", Map.of());
    clock.advance(Duration.ofSeconds(250));
    client.search("ORG001", Map.of());
    clock.advance(Duration.ofSeconds(30));
    client.search("ORG001", Map.of());

    assertThat(bearers).containsExactly("Bearer tok-1", "Bearer tok-1", "Bearer tok-2");
  }

  @Test
  void aRefusedTokenIsForgottenAndTheCallIsRetryable() {
    client.search("ORG001", Map.of());
    rejectNextQuery = true;

    assertThatThrownBy(() -> client.search("ORG001", Map.of()))
        .isInstanceOf(OrchestratorUnavailableException.class);
    client.search("ORG001", Map.of());

    assertThat(bearers).containsExactly("Bearer tok-1", "Bearer tok-1", "Bearer tok-2");
  }

  @Test
  void noTokenIsAnUnavailableOrchestratorAndTheSecretIsNeverShown() {
    tokenStatus = 401;

    assertThatThrownBy(() -> client.search("ORG001", Map.of()))
        .isInstanceOf(OrchestratorUnavailableException.class)
        .hasMessageNotContaining(SECRET);
    assertThat(bearers).isEmpty();
  }

  @Test
  void propertiesNeverPrintTheSecret() {
    OrchestratorClientProperties.Auth auth =
        new OrchestratorClientProperties.Auth(URI.create("http://k/t"), "mw-portal-api", SECRET);
    assertThat(auth.toString()).doesNotContain(SECRET).contains("mw-portal-api");
  }
}
