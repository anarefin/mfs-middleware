package mfs.mw.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.auth.config.CoreClientProperties;
import mfs.mw.auth.contracts.OpenApiSchemas;
import mfs.mw.auth.dto.CryptoTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth's core client (ticket 95b, ADR D31) against a local HTTP server: the KYC factors check goes to
 * {@code POST /core/v1/customers/{walletId}/verify-factors} with the service token and the factors as
 * hashes; 200 is VERIFIED, 422 {@code MW-CORE-<reason>} REJECTED with the reason, a 5xx or timeout is
 * retried (the check is idempotent by txnId) and then UNKNOWN; a 401 renews the token once.
 */
class CoreClientTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TXN = "0190a7c4-0000-7000-8000-000000000001";
  private static final UUID RESET = UUID.fromString("5f0e8a52-1d4c-4a8e-9a43-6b9f3c2d1e00");
  private static final CryptoTokens FACTORS = new CryptoTokens("NID", "tokenise-nid.v1", List.of("NID_AAAA", "NID_BBBB"));

  private record Seen(String path, String authorization, JsonNode body) {}

  private record Answer(int status, String body, long delayMs) {}

  private HttpServer server;
  private final List<Seen> seen = new CopyOnWriteArrayList<>();
  private final Deque<Answer> answers = new ArrayDeque<>();
  private final ServiceTokenClient tokens = mock(ServiceTokenClient.class);
  private CoreClient client;

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
    CoreClientProperties props = new CoreClientProperties(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofMillis(300), Duration.ofMillis(300),
        3, Duration.ofMillis(10));
    client = new CoreClient(props, tokens, CircuitBreakerRegistry.ofDefaults(), JSON);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void answer(int status, String body) {
    answers.add(new Answer(status, body, 0));
  }

  @Test
  void verifiedFactorsAreAskedWithTheTokenAndTheHashesOnly() {
    answer(200, "{\"txnId\":\"" + TXN + "\",\"walletId\":\"01711000001\",\"status\":\"VERIFIED\",\"coreRef\":\"C-1\",\"resetId\":\"" + RESET + "\"}");

    CoreClient.FactorsAnswer a = client.verifyFactors("01711000001", TXN, RESET, FACTORS);

    assertThat(a.outcome()).isEqualTo(CoreClient.Outcome.VERIFIED);
    assertThat(seen).hasSize(1);
    Seen s = seen.get(0);
    assertThat(s.path()).isEqualTo("/core/v1/customers/01711000001/verify-factors");
    assertThat(s.authorization()).isEqualTo("Bearer tok-1");
    assertThat(s.body().path("txnId").asString()).isEqualTo(TXN);
    assertThat(s.body().path("resetId").asString()).isEqualTo(RESET.toString());
    assertThat(s.body().path("factors").path("nidLast4Token").asString()).isEqualTo("NID_AAAA");
    assertThat(s.body().path("factors").path("dateOfBirthToken").asString()).isEqualTo("NID_BBBB");
    assertThat(s.body().path("factors").path("tokenKid").asString()).isEqualTo("tokenise-nid.v1");
    assertThat(OpenApiSchemas.component("api/mw-core-mock/core-wallet.v1.openapi.yaml", "FactorsRequest").validate(s.body()))
        .as("the body against core-wallet.v1 FactorsRequest").isEmpty();
  }

  @Test
  void aCoreRefusalIsRejectedWithTheCoreReason() {
    answer(422, "{\"code\":\"MW-CORE-FACTORS_MISMATCH\",\"message\":\"no\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");

    CoreClient.FactorsAnswer a = client.verifyFactors("01711000001", TXN, RESET, FACTORS);

    assertThat(a.outcome()).isEqualTo(CoreClient.Outcome.REJECTED);
    assertThat(a.reason()).isEqualTo("FACTORS_MISMATCH");
    assertThat(seen).as("a refusal is final").hasSize(1);
  }

  @Test
  void aLostAnswerIsAskedAgainUnderTheSameTxnIdThenUnknown() {
    answer(504, "{\"code\":\"MW-TMO-CORE_ANSWER_LOST\"}");
    answer(200, "{\"status\":\"VERIFIED\"}");

    assertThat(client.verifyFactors("01711000001", TXN, RESET, FACTORS).outcome()).isEqualTo(CoreClient.Outcome.VERIFIED);
    assertThat(seen).hasSize(2);
    assertThat(seen.get(1).body().path("txnId").asString()).isEqualTo(TXN);

    seen.clear();
    answers.add(new Answer(200, "{}", 1000));
    answer(503, "{}");
    answer(500, "{}");
    assertThat(client.verifyFactors("01711000001", TXN, RESET, FACTORS).outcome()).isEqualTo(CoreClient.Outcome.UNKNOWN);
    assertThat(seen).as("timeout, 503, 500: three tries").hasSize(3);
  }

  @Test
  void aRefusedServiceTokenIsRenewedOnce() {
    answer(401, "{\"code\":\"MW-AUTH-001\"}");
    answer(200, "{\"status\":\"VERIFIED\"}");

    assertThat(client.verifyFactors("01711000001", TXN, RESET, FACTORS).outcome()).isEqualTo(CoreClient.Outcome.VERIFIED);
    verify(tokens).invalidate();
  }

  @Test
  void aWalletIdIsOnePathSegmentWhateverItHolds() {
    answer(200, "{\"status\":\"VERIFIED\"}");

    client.verifyFactors("../balance/x?y=1", TXN, RESET, FACTORS);

    assertThat(seen.get(0).path()).isEqualTo("/core/v1/customers/..%2Fbalance%2Fx%3Fy%3D1/verify-factors");
  }
}
