package mfs.mw.admin.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.admin.api.config.CoreClientProperties;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-admin-api's core client (ticket 95b, ADR D31) against a local HTTP server: an approved unlock goes
 * to {@code POST /core/v1/accounts/{walletId}/unlock} with the service token and a body the contract
 * accepts; 200 is UNLOCKED, 422 {@code MW-CORE-<reason>} REFUSED with the reason, a 5xx or timeout is
 * asked again under the same txnId and then UNKNOWN; a 401 renews the token once.
 */
class CoreAccountClientTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TXN = "0190a7c4-0000-7000-8000-000000000001";
  private static final String CR = "0d6c3f0e-2222-4b5e-9a1e-000000000003";

  private record Seen(String path, String authorization, JsonNode body) {}

  private record Answer(int status, String body, long delayMs) {}

  private HttpServer server;
  private final List<Seen> seen = new CopyOnWriteArrayList<>();
  private final Deque<Answer> answers = new ArrayDeque<>();
  private final ServiceTokenClient tokens = mock(ServiceTokenClient.class);
  private CoreAccountClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      byte[] in = ex.getRequestBody().readAllBytes();
      seen.add(new Seen(ex.getRequestURI().getRawPath(), ex.getRequestHeaders().getFirst("Authorization"),
          in.length == 0 ? null : JSON.readTree(in)));
      Answer a;
      synchronized (answers) {
        a = answers.isEmpty() ? new Answer(500, "{}", 0) : answers.poll();
      }
      if (a.delayMs() > 0) {
        try {
          Thread.sleep(a.delayMs());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] out = a.body().getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(a.status(), out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    when(tokens.token()).thenReturn("tok-1");
    client = new CoreAccountClient(new CoreClientProperties(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofMillis(300), Duration.ofMillis(300),
        3, Duration.ofMillis(10)), tokens, JSON);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void answer(int status, String body) {
    answers.add(new Answer(status, body, 0));
  }

  @Test
  void anUnlockIsSentWithTheTokenAndAContractBody() {
    answer(200, "{\"txnId\":\"" + TXN + "\",\"walletId\":\"01711000123\",\"status\":\"UNLOCKED\",\"coreRef\":\"C-1\"}");

    CoreAccountClient.UnlockAnswer a = client.unlock("01711000123", TXN, CR, "ops-maker01", "ops-checker01");

    assertThat(a.outcome()).isEqualTo(CoreAccountClient.Outcome.UNLOCKED);
    Seen s = seen.get(0);
    assertThat(s.path()).isEqualTo("/core/v1/accounts/01711000123/unlock");
    assertThat(s.authorization()).isEqualTo("Bearer tok-1");
    assertThat(s.body().path("txnId").asString()).isEqualTo(TXN);
    assertThat(s.body().path("changeRequestId").asString()).isEqualTo(CR);
    assertThat(s.body().path("makerId").asString()).isEqualTo("ops-maker01");
    assertThat(s.body().path("checkerId").asString()).isEqualTo("ops-checker01");
    assertThat(OpenApiSchemas.component("api/mw-core-mock/core-wallet.v1.openapi.yaml", "UnlockRequest").validate(s.body()))
        .as("the body against core-wallet.v1 UnlockRequest").isEmpty();
  }

  @Test
  void aCoreRefusalIsFinalWithTheCoreReason() {
    answer(422, "{\"code\":\"MW-CORE-WALLET_NOT_LOCKED\",\"message\":\"no\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");

    CoreAccountClient.UnlockAnswer a = client.unlock("01711000123", TXN, CR, "ops-maker01", "ops-checker01");

    assertThat(a.outcome()).isEqualTo(CoreAccountClient.Outcome.REFUSED);
    assertThat(a.reason()).isEqualTo("WALLET_NOT_LOCKED");
    assertThat(seen).hasSize(1);
  }

  @Test
  void noDefiniteAnswerIsAskedAgainUnderTheSameTxnIdThenUnknown() {
    answer(504, "{\"code\":\"MW-TMO-CORE_ANSWER_LOST\"}");
    answer(200, "{\"status\":\"UNLOCKED\"}");
    assertThat(client.unlock("01711000123", TXN, CR, "m", "c").outcome()).isEqualTo(CoreAccountClient.Outcome.UNLOCKED);
    assertThat(seen).extracting(x -> x.body().path("txnId").asString()).containsExactly(TXN, TXN);

    seen.clear();
    answers.add(new Answer(200, "{}", 1000));
    answer(503, "{}");
    answer(500, "{}");
    assertThat(client.unlock("01711000123", TXN, CR, "m", "c").outcome()).isEqualTo(CoreAccountClient.Outcome.UNKNOWN);
    assertThat(seen).hasSize(3);
  }

  @Test
  void aRefusedServiceTokenIsRenewedOnceAndAWalletIdIsOnePathSegment() {
    answer(401, "{\"code\":\"MW-AUTH-001\"}");
    answer(200, "{\"status\":\"UNLOCKED\"}");

    assertThat(client.unlock("../x?y=1", TXN, CR, "m", "c").outcome()).isEqualTo(CoreAccountClient.Outcome.UNLOCKED);

    verify(tokens).invalidate();
    assertThat(seen.get(1).path()).isEqualTo("/core/v1/accounts/..%2Fx%3Fy%3D1/unlock");
  }
}
