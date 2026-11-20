package mfs.mw.recon.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.recon.config.CoreClientProperties;
import mfs.mw.recon.exception.MwException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The e-money total from the core (ticket 127c, REC-07): a realm services client-credentials token as Bearer, the
 * contract's {@code EmoneyTotal} read back as poisha, a refused token renewed once, anything else a 503.
 */
class CoreEmoneyClientTest {

  private static final String BODY = """
      {"asOf":"2026-11-19T18:45:00Z","total":{"valueMinor":123456789,"currency":"BDT"},"walletCount":3,
       "byType":[{"type":"CUSTOMER","walletCount":3,"total":{"valueMinor":123456789,"currency":"BDT"}}]}""";

  private HttpServer server;
  private final List<String> auth = new CopyOnWriteArrayList<>();
  private final AtomicInteger tokens = new AtomicInteger();
  private volatile int[] coreStatuses = {200};
  private final AtomicInteger coreCalls = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", ex -> {
      int n = tokens.incrementAndGet();
      auth.add("token:" + ex.getRequestHeaders().getFirst("Authorization"));
      reply(ex, 200, "{\"access_token\":\"tok-" + n + "\",\"expires_in\":300}");
    });
    server.createContext("/core/v1/reports/emoney-total", ex -> {
      int i = Math.min(coreCalls.getAndIncrement(), coreStatuses.length - 1);
      auth.add("core:" + ex.getRequestHeaders().getFirst("Authorization"));
      int status = coreStatuses[i];
      reply(ex, status, status == 200 ? BODY
          : "{\"code\":\"" + (status == 401 ? "MW-AUTH-001" : "MW-SYS-001") + "\",\"message\":\"x\"}");
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private CoreEmoneyClient client() {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    CoreClientProperties props = new CoreClientProperties(URI.create(base), URI.create(base + "/token"), "mw-recon",
        "fake-recon-secret", Duration.ofSeconds(2), Duration.ofSeconds(2));
    JsonMapper json = JsonMapper.builder().build();
    return new CoreEmoneyClient(props, new ServiceTokenClient(props, json, Clock.systemUTC()), json);
  }

  @Test
  void theTotalIsReadWithTheServiceToken() {
    assertThat(client().emoneyTotalMinor()).isEqualTo(123_456_789L);
    assertThat(auth).containsExactly("token:Basic bXctcmVjb246ZmFrZS1yZWNvbi1zZWNyZXQ=", "core:Bearer tok-1");
  }

  @Test
  void aRefusedTokenIsRenewedOnce() {
    coreStatuses = new int[] {401, 200};
    assertThat(client().emoneyTotalMinor()).isEqualTo(123_456_789L);
    assertThat(tokens).hasValue(2);
    assertThat(auth).contains("core:Bearer tok-2");
  }

  @Test
  void aCoreFailureIsA503AndNoNumber() {
    coreStatuses = new int[] {500};
    assertThatThrownBy(() -> client().emoneyTotalMinor()).isInstanceOfSatisfying(MwException.class,
        e -> assertThat(e.code()).isEqualTo("MW-SYS-002"));
  }

  private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }
}
