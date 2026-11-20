package mfs.mw.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.dto.HoldPayload;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.QueryPayload;
import mfs.mw.orchestrator.dto.TransferPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet client's transport (ADR D31, ticket 95a) against a local HTTP server: which call
 * each step makes, the assertion in its header, and how each answer is classified (2xx answered, 422
 * / 401 MW-AUTH-046 refused, 5xx / timeout ambiguous), with the token refreshed once and only reads
 * retried.
 */
class CoreWalletClientTest {

  private static final String TXN = "0190a7c4-0000-7000-8000-000000000001";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private record Seen(String method, String path, Map<String, String> headers, JsonNode body) {}

  private HttpServer server;
  private final List<Seen> seen = new CopyOnWriteArrayList<>();
  private final Map<String, Function<HttpExchange, Response>> routes = new ConcurrentHashMap<>();
  private final AtomicInteger tokens = new AtomicInteger();
  private CoreWalletClient client;

  private record Response(int status, String body, long delayMs) {
    static Response ok(String body) {
      return new Response(200, body, 0);
    }
  }

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", ex -> {
      tokens.incrementAndGet();
      reply(ex, new Response(200, "{\"access_token\":\"tok-" + tokens.get() + "\",\"expires_in\":300}", 0));
    });
    server.createContext("/core/v1/", ex -> {
      byte[] in = ex.getRequestBody().readAllBytes();
      Map<String, String> h = new ConcurrentHashMap<>();
      ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
      seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(), h, in.length == 0 ? null : JSON.readTree(in)));
      String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
      reply(ex, routes.getOrDefault(key, x -> new Response(404, "{}", 0)).apply(ex));
    });
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    OrchestratorProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
        "mw.orchestrator.core.base-url", base,
        "mw.orchestrator.core.token-uri", base + "/token",
        "mw.orchestrator.core.client-secret", "test-only-not-a-secret",
        "mw.orchestrator.core.read-timeout", "300ms")))
        .bindOrCreate("mw.orchestrator", OrchestratorProperties.class);
    RetryRegistry retries = RetryRegistry.of(RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(10)).build());
    client = new CoreWalletClient(props, new ServiceTokenClient(props, JSON, Clock.systemUTC()),
        CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(), retries, JSON);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static void reply(HttpExchange ex, Response r) throws IOException {
    if (r.delayMs() > 0) {
      try {
        Thread.sleep(r.delayMs());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    try {
      ex.sendResponseHeaders(r.status(), out.length);
      ex.getResponseBody().write(out);
    } catch (IOException e) {
      // the client gave up (timeout)
    }
    ex.close();
  }

  private static CoreCommand transfer(String type, String step, String assertion) {
    return new CoreCommand(1, type, TXN + ":" + step, TXN, "corr-1", Instant.now(), null,
        new TransferPayload("P2P", new Party("WALLET", "01711000001", null, null), new Party("WALLET", "01711000002", null, null),
            Amount.bdt(10_000), assertion, assertion == null ? null : "key-1"));
  }

  private static String error(String code) {
    return "{\"code\":\"" + code + "\",\"message\":\"m\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}";
  }

  @Test
  void aTransferCarriesTheAssertionInItsHeaderAndTheServiceToken() {
    routes.put("POST /core/v1/transfers", ex -> Response.ok("{\"txnId\":\"" + TXN + "\",\"status\":\"POSTED\",\"coreRef\":\"CORE-1\"}"));

    CoreReply reply = client.call(transfer("PostTransfer", "post", "a.b.c"));

    assertThat(reply.kind()).isEqualTo(CoreReply.Kind.ANSWERED);
    assertThat(reply.body().path("coreRef").asString()).isEqualTo("CORE-1");
    Seen s = seen.get(0);
    assertThat(s.headers()).containsEntry("x-txn-assertion", "a.b.c").containsEntry("authorization", "Bearer tok-1")
        .containsEntry("x-correlation-id", "corr-1");
    assertThat(s.body().path("idempotencyKey").asString()).isEqualTo("key-1");
    assertThat(s.body().has("assertion")).as("never in the body").isFalse();
    assertThat(s.body().path("txnId").asString()).isEqualTo(TXN);
  }

  @Test
  void eachStepCallsItsEndpoint() {
    routes.put("POST /core/v1/transactions/validate", ex -> Response.ok("{\"status\":\"VALIDATED\",\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"}}"));
    routes.put("POST /core/v1/holds/HOLD-1/commit", ex -> Response.ok("{\"status\":\"COMMITTED\"}"));
    routes.put("POST /core/v1/holds/HOLD-1/release", ex -> Response.ok("{\"status\":\"RELEASED\"}"));
    routes.put("GET /core/v1/transactions/" + TXN, ex -> Response.ok("{\"status\":\"POSTED\",\"entries\":[]}"));

    assertThat(client.call(transfer("ValidateTransaction", "validate", "a.b.c")).kind()).isEqualTo(CoreReply.Kind.ANSWERED);
    assertThat(client.call(new CoreCommand(1, "CommitHold", TXN + ":commit:manual-2", TXN, "c", Instant.now(), null,
        new HoldPayload("HOLD-1", null))).kind()).isEqualTo(CoreReply.Kind.ANSWERED);
    assertThat(client.call(new CoreCommand(1, "ReleaseHold", TXN + ":release", TXN, "c", Instant.now(), null,
        new HoldPayload("HOLD-1", null))).kind()).isEqualTo(CoreReply.Kind.ANSWERED);
    assertThat(client.call(new CoreCommand(1, "QueryTransaction", TXN + ":query:1", TXN, "c", Instant.now(), null,
        new QueryPayload(null))).body().path("status").asString()).isEqualTo("POSTED");

    assertThat(seen).extracting(Seen::path).containsExactly("/core/v1/transactions/validate",
        "/core/v1/holds/HOLD-1/commit", "/core/v1/holds/HOLD-1/release", "/core/v1/transactions/" + TXN);
    assertThat(seen.get(1).body().path("round").asInt()).as("an operator's second forced commit").isEqualTo(2);
    assertThat(seen.get(2).body() == null || !seen.get(2).body().has("round")).as("the saga's own release").isTrue();
  }

  @Test
  void aBalanceInquiryReadsTheWalletWithTheAssertionTxnAndKey() {
    routes.put("GET /core/v1/balance/01711000001",
        ex -> Response.ok("{\"walletId\":\"01711000001\",\"available\":{\"valueMinor\":1,\"currency\":\"BDT\"}}"));

    client.call(transfer("BalanceInquiry", "balance", "a.b.c"));

    assertThat(seen.get(0).path()).isEqualTo("/core/v1/balance/01711000001?txnId=" + TXN);
    assertThat(seen.get(0).headers()).containsEntry("x-txn-assertion", "a.b.c").containsEntry("idempotency-key", "key-1");
  }

  @Test
  void aCoreRefusalIsRefusedWithItsCode() {
    routes.put("POST /core/v1/transfers", ex -> new Response(422, error("MW-CORE-INSUFFICIENT_BALANCE"), 0));
    routes.put("POST /core/v1/holds", ex -> new Response(401, error("MW-AUTH-046"), 0));

    CoreReply refused = client.call(transfer("PostTransfer", "post", "a.b.c"));
    CoreReply assertion = client.call(transfer("HoldFunds", "hold", "a.b.c"));

    assertThat(refused.kind()).isEqualTo(CoreReply.Kind.REFUSED);
    assertThat(refused.code()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(assertion.kind()).isEqualTo(CoreReply.Kind.REFUSED);
    assertThat(assertion.code()).isEqualTo("MW-AUTH-046");
  }

  @Test
  void aServerErrorOrATimeoutIsAmbiguousAndAMoneyCallIsNotRetried() {
    routes.put("POST /core/v1/transfers", ex -> new Response(503, error("MW-SYS-CORE_REPLY_DROPPED"), 0));
    routes.put("POST /core/v1/holds", ex -> new Response(200, "{}", 1_000));

    assertThat(client.call(transfer("PostTransfer", "post", "a.b.c")).kind()).isEqualTo(CoreReply.Kind.AMBIGUOUS);
    CoreReply timedOut = client.call(transfer("HoldFunds", "hold", "a.b.c"));

    assertThat(timedOut.kind()).isEqualTo(CoreReply.Kind.AMBIGUOUS);
    assertThat(timedOut.status()).isZero();
    assertThat(seen).as("one call each: the saga's deadline resends money").hasSize(2);
  }

  @Test
  void aReadIsRetriedOnAServerError() {
    AtomicInteger calls = new AtomicInteger();
    routes.put("GET /core/v1/transactions/" + TXN, ex -> calls.incrementAndGet() < 3
        ? new Response(503, error("MW-SYS-001"), 0) : Response.ok("{\"status\":\"HELD\",\"entries\":[]}"));

    CoreReply reply = client.call(new CoreCommand(1, "QueryTransaction", TXN + ":query:1", TXN, "c", Instant.now(), null,
        new QueryPayload(null)));

    assertThat(reply.kind()).isEqualTo(CoreReply.Kind.ANSWERED);
    assertThat(calls).hasValue(3);
  }

  @Test
  void aRefusedServiceTokenIsRenewedOnceAndTheCallRepeated() {
    AtomicInteger calls = new AtomicInteger();
    routes.put("POST /core/v1/transfers", ex -> calls.incrementAndGet() == 1
        ? new Response(401, error("MW-AUTH-001"), 0) : Response.ok("{\"status\":\"POSTED\",\"coreRef\":\"C\"}"));

    assertThat(client.call(transfer("PostTransfer", "post", "a.b.c")).kind()).isEqualTo(CoreReply.Kind.ANSWERED);
    assertThat(tokens).hasValue(2);
    assertThat(seen.get(1).headers()).containsEntry("authorization", "Bearer tok-2");
  }

  /** FRS 10.3: the client's request bodies against the core's own contract (mw-core-mock's OpenAPI file). */
  @Test
  void theRequestBodiesMatchTheCoreWalletContract() {
    String api = "api/mw-core-mock/core-wallet.v1.openapi.yaml";
    routes.put("POST /core/v1/transactions/validate", ex -> Response.ok("{}"));
    routes.put("POST /core/v1/holds/HOLD-1/commit", ex -> Response.ok("{}"));
    routes.put("POST /core/v1/credits", ex -> Response.ok("{}"));
    routes.put("POST /core/v1/reversals", ex -> Response.ok("{}"));
    Party wallet = new Party("WALLET", "01711000002", null, null);

    client.call(transfer("ValidateTransaction", "validate", "a.b.c"));
    client.call(new CoreCommand(1, "CommitHold", TXN + ":commit:manual-1", TXN, "c", Instant.now(), null,
        new HoldPayload("HOLD-1", null)));
    client.call(new CoreCommand(1, "CreditWallet", TXN + ":credit", TXN, "c", Instant.now(), null,
        new mfs.mw.orchestrator.dto.CreditPayload("ADD_MONEY_BANK", new Party("BANK_ACCOUNT", "1234567890", null, null),
            wallet, Amount.bdt(5_000))));
    client.call(new CoreCommand(1, "ReverseTransaction", TXN + ":reverse", TXN, "c", Instant.now(), null,
        new mfs.mw.orchestrator.dto.ReversePayload("0190a7c4-0000-7000-8000-000000000002", Amount.bdt(5_000), null, null)));

    var schemas = List.of("TransferRequest", "HoldActionRequest", "CreditRequest", "ReversalRequest");
    assertThat(seen).hasSize(4);
    for (int i = 0; i < 4; i++) {
      assertThat(mfs.mw.orchestrator.contracts.OpenApiSchemas.component(api, schemas.get(i)).validate(seen.get(i).body()))
          .as("%s: %s", schemas.get(i), seen.get(i).body()).isEmpty();
    }
  }

  @Test
  void anUnreachableCoreIsAmbiguous() {
    server.stop(0);

    assertThat(client.call(transfer("PostTransfer", "post", "a.b.c")).kind()).isEqualTo(CoreReply.Kind.AMBIGUOUS);
  }
}
