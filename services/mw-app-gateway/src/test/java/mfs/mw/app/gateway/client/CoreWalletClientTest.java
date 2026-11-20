package mfs.mw.app.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.app.gateway.config.CoreClientProperties;
import mfs.mw.app.gateway.contracts.ServiceJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/** WAL-01, WAL-05 and WAL-12 as mw-app-gateway asks them (ticket 107b). No Spring context. */
class CoreWalletClientTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final String LIMITS_BODY =
      "{\"walletId\":\"01711000001\",\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":100,\"currency\":\"BDT\"}}";
  static final String ACCOUNT_BODY =
      "{\"walletId\":\"01711000001\",\"msisdn\":\"01711000001\",\"type\":\"CUSTOMER\",\"status\":\"ACTIVE\","
          + "\"kycLevel\":\"FULL\",\"holderName\":\"Rahim Uddin\"}";

  private HttpServer server;
  private final CopyOnWriteArrayList<Seen> seen = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final ServiceTokenClient tokens = mock(ServiceTokenClient.class);
  private CoreWalletClient client;

  @BeforeEach
  void up() throws IOException {
    when(tokens.token()).thenReturn("svc-token");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] req = exchange.getRequestBody().readAllBytes();
      seen.add(new Seen(
          exchange.getRequestMethod(),
          exchange.getRequestURI().getRawPath(),
          exchange.getRequestURI().getRawQuery(),
          exchange.getRequestHeaders().getFirst("Authorization"),
          exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
          new String(req, StandardCharsets.UTF_8)));
      int code = status.get();
      byte[] body = answer(exchange.getRequestURI().getRawPath(), code);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(code, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
    client = new CoreWalletClient(
        new CoreClientProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            Duration.ofSeconds(1), Duration.ofSeconds(1)),
        tokens, JSON);
  }

  @AfterEach
  void down() {
    server.stop(0);
    MDC.clear();
  }

  @Test
  void accountLimitsAndStatementUseTheServiceToken() {
    MDC.put("correlationId", "corr-1");
    assertThat(client.account("01711000001").holderName()).isEqualTo("Rahim Uddin");
    assertThat(client.limits("01711000001", "P2P", 100).remaining().daily().valueMinor()).isEqualTo(1_500_000L);
    Instant from = Instant.parse("2026-08-01T00:00:00Z");
    Instant to = Instant.parse("2026-10-30T00:00:00Z");
    assertThat(client.statement("01711000001", from, to, 100, 0).entries()).hasSize(1);

    assertThat(seen).hasSize(3);
    assertThat(seen).allSatisfy(s -> {
      assertThat(s.auth()).isEqualTo("Bearer svc-token");
      assertThat(s.correlation()).isEqualTo("corr-1");
    });
    assertThat(seen.get(0).method()).isEqualTo("GET");
    assertThat(seen.get(0).path()).isEqualTo("/core/v1/accounts/01711000001");
    assertThat(JSON.readTree(seen.get(1).body())).isEqualTo(JSON.readTree(LIMITS_BODY));
    assertThat(seen.get(1).path()).isEqualTo("/core/v1/limits/check");
    assertThat(seen.get(2).query()).contains("walletId=01711000001");
    assertThat(seen.get(2).query()).contains("from=2026-08-01T00%3A00%3A00Z");
    assertThat(seen.get(2).query()).contains("to=2026-10-30T00%3A00%3A00Z");
    assertThat(seen.get(2).query()).contains("limit=100").contains("page=0");
  }

  @Test
  void aRefusedTokenIsAskedOnceMore() {
    status.set(401);
    assertThatThrownBy(() -> client.account("01711000001")).isInstanceOf(CoreRefusedException.class);
    verify(tokens, times(2)).token();
    verify(tokens).invalidate();
    assertThat(seen).hasSize(2);
  }

  @Test
  void aCoreThatCannotBeReachedIsUnavailable() throws IOException {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) {
      closed = socket.getLocalPort();
    }
    CoreWalletClient down = new CoreWalletClient(
        new CoreClientProperties(URI.create("http://127.0.0.1:" + closed), Duration.ofMillis(200), Duration.ofMillis(200)),
        tokens, JSON);
    assertThatThrownBy(() -> down.account("01711000001")).isInstanceOf(CoreUnavailableException.class);
  }

  private static byte[] answer(String path, int code) {
    if (code != 200) {
      return "{\"code\":\"MW-AUTH-001\",\"message\":\"Authentication required\"}".getBytes(StandardCharsets.UTF_8);
    }
    if (path.startsWith("/core/v1/accounts/")) {
      return ACCOUNT_BODY.getBytes(StandardCharsets.UTF_8);
    }
    if (path.startsWith("/core/v1/limits")) {
      return """
          {"walletId":"01711000001","allowed":true,"remaining":{"perTxn":{"valueMinor":500000,"currency":"BDT"},"daily":{"valueMinor":1500000,"currency":"BDT"}}}
          """.getBytes(StandardCharsets.UTF_8);
    }
    return """
        {"walletId":"01711000001","from":"2026-08-01T00:00:00Z","to":"2026-10-30T00:00:00Z","page":0,"hasMore":false,
         "entries":[{"txnId":"019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d","walletId":"01711000001","direction":"DEBIT",
         "kind":"TRANSFER","amount":{"valueMinor":100000,"currency":"BDT"},"balanceAfter":{"valueMinor":900000,"currency":"BDT"},
         "at":"2026-08-01T00:00:00Z"}]}
        """.getBytes(StandardCharsets.UTF_8);
  }

  private record Seen(String method, String path, String query, String auth, String correlation, String body) {}
}
