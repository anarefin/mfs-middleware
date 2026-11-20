package mfs.mw.ussd.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.ussd.client.CoreAccountClient;
import mfs.mw.ussd.client.ServiceTokenClient;
import mfs.mw.ussd.config.AuthClientProperties;
import mfs.mw.ussd.config.CoreClientProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WAL-01 {@code GET /core/v1/accounts/{msisdn}} as {@link CoreAccountClient} calls it: the answer
 * matches the Account schema and the client reads {@code type} with its service token.
 */
class CoreAccountClientTest {

  static final String ACCOUNT = """
      {"walletId":"w-01712345678","msisdn":"01712345678","type":"CUSTOMER","status":"ACTIVE",
       "kycLevel":"FULL","holderName":"A"}""";

  HttpServer server;
  AtomicReference<String> accountAuth = new AtomicReference<>();
  AtomicReference<String> accountPath = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", this::token);
    server.createContext("/core/v1/accounts/01712345678", this::account);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void theAccountAnswerMatchesTheContractAndTheClientReadsTheType() {
    assertThat(OpenApiSchemas.component(ApiContractsTest.CORE_API, "Account")
        .validate(ApiContractsTest.JSON.readTree(ACCOUNT))).isEmpty();

    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    ServiceTokenClient tokens = new ServiceTokenClient(
        new AuthClientProperties(base, URI.create(base + "/token"), "mw-ussd", "secret",
            Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMinutes(5)),
        ApiContractsTest.JSON, Clock.systemUTC());
    CoreAccountClient client = new CoreAccountClient(
        new CoreClientProperties("http", base, Duration.ofSeconds(1), Duration.ofSeconds(2)),
        tokens, ApiContractsTest.JSON);

    assertThat(client.type("01712345678")).contains("CUSTOMER");
    assertThat(accountPath.get()).isEqualTo("/core/v1/accounts/01712345678");
    assertThat(accountAuth.get()).isEqualTo("Bearer tok");
  }

  private void token(HttpExchange ex) throws IOException {
    byte[] body = "{\"access_token\":\"tok\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, body.length);
    ex.getResponseBody().write(body);
    ex.close();
  }

  private void account(HttpExchange ex) throws IOException {
    accountAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
    accountPath.set(ex.getRequestURI().getPath());
    byte[] body = ACCOUNT.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, body.length);
    ex.getResponseBody().write(body);
    ex.close();
  }
}
