package mfs.mw.orchestrator.messaging;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The orchestrator's own stand-in for the core wallet REST API (ticket 95a; replaces the Kafka
 * CoreStub of ticket 08): a local HTTP server answering {@code /core/v1} by a fixed rule, with no
 * knowledge of mw-core-mock, and a token endpoint. Validation and transfers succeed (fee 500), except
 * that an amount of {@link #REJECT_AMOUNT} is refused at validation (422 MW-CORE-INSUFFICIENT_BALANCE)
 * and a transfer of {@link #SILENT_AMOUNT} is posted but answered only after {@link #SILENT_DELAY_MS},
 * long after the client's read timeout (a lost answer). Holds, commits, releases, credits and reversals
 * succeed; WAL-13 tells what the stub did; a balance is {@link #AVAILABLE}, except for {@link
 * #UNKNOWN_WALLET} (422 MW-CORE-WALLET_NOT_FOUND). Every money operation is idempotent by txnId.
 */
public final class CoreRestStub implements AutoCloseable {

  public static final long REJECT_AMOUNT = 99_999;
  public static final long SILENT_AMOUNT = 77_777;
  public static final long SILENT_DELAY_MS = 4_000;
  public static final String UNKNOWN_WALLET = "01711999999";
  public static final long AVAILABLE = 1_200_000;

  /** One request the stub served. */
  public record Seen(String method, String path, Map<String, String> headers, JsonNode body) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final List<Seen> seen = new CopyOnWriteArrayList<>();
  private final Map<String, String> status = new ConcurrentHashMap<>();
  private final Map<String, String> holds = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> postings = new ConcurrentHashMap<>();

  private CoreRestStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", ex -> send(ex, 200, "{\"access_token\":\"stub-token\",\"expires_in\":300}", 0));
    server.createContext("/core/v1/", this::core);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /** A started stub. */
  public static CoreRestStub start() {
    try {
      return new CoreRestStub();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Points the orchestrator's core client at this stub. */
  public void register(DynamicPropertyRegistry registry) {
    registry.add("mw.orchestrator.core.base-url", this::baseUrl);
    registry.add("mw.orchestrator.core.token-uri", () -> baseUrl() + "/token");
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Every core request served so far. */
  public List<Seen> requests() {
    return seen;
  }

  /** The requests made for {@code txnId} (in the body, the path or the query). */
  public List<Seen> requests(String txnId) {
    return seen.stream().filter(s -> s.path().contains(txnId)
        || (s.body() != null && txnId.equals(s.body().path("txnId").asString(null)))).toList();
  }

  /** The saga steps the core was called for under {@code txnId}, as core call names, in order. */
  public List<String> calls(String txnId) {
    return seen.stream().map(s -> {
      String p = s.path();
      boolean mine = p.contains(txnId) || (s.body() != null && txnId.equals(s.body().path("txnId").asString(null)));
      if (p.matches("/core/v1/holds/[^/]+/(commit|release)")) {
        mine = txnId.equals(holds.get(p.split("/")[4]));
      }
      if (!mine) {
        return null;
      }
      if (p.equals("/core/v1/transactions/validate")) {
        return "ValidateTransaction";
      } else if (p.equals("/core/v1/transfers")) {
        return "PostTransfer";
      } else if (p.equals("/core/v1/holds")) {
        return "HoldFunds";
      } else if (p.endsWith("/commit")) {
        return "CommitHold";
      } else if (p.endsWith("/release")) {
        return "ReleaseHold";
      } else if (p.equals("/core/v1/credits")) {
        return "CreditWallet";
      } else if (p.equals("/core/v1/reversals")) {
        return "ReverseTransaction";
      } else if (p.startsWith("/core/v1/transactions/")) {
        return "QueryTransaction";
      } else if (p.startsWith("/core/v1/balance/")) {
        return "BalanceInquiry";
      }
      return p;
    }).filter(java.util.Objects::nonNull).toList();
  }

  /** How many times a transfer of {@code txnId} moved money (once, whatever the repeats). */
  public int postings(String txnId) {
    AtomicInteger n = postings.get(txnId);
    return n == null ? 0 : n.get();
  }

  private void core(HttpExchange ex) throws IOException {
    byte[] in = ex.getRequestBody().readAllBytes();
    JsonNode body = in.length == 0 ? null : JSON.readTree(in);
    Map<String, String> headers = new ConcurrentHashMap<>();
    ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
    String path = ex.getRequestURI().getPath();
    String query = ex.getRequestURI().getQuery();
    seen.add(new Seen(ex.getRequestMethod(), query == null ? path : path + "?" + query, headers, body));
    String m = ex.getRequestMethod();
    if ("POST".equals(m) && path.equals("/core/v1/transactions/validate")) {
      validate(ex, body);
    } else if ("POST".equals(m) && path.equals("/core/v1/transfers")) {
      transfer(ex, body);
    } else if ("POST".equals(m) && path.equals("/core/v1/holds")) {
      String txn = body.path("txnId").asString();
      String holdId = "HOLD-" + txn.substring(0, 8);
      holds.put(holdId, txn);
      status.put(txn, "HELD");
      send(ex, 200, result(txn, "HELD").put("holdId", holdId).toString(), 0);
    } else if ("POST".equals(m) && path.matches("/core/v1/holds/[^/]+/(commit|release)")) {
      String holdId = path.split("/")[4];
      String txn = holds.getOrDefault(holdId, "unknown");
      String state = path.endsWith("commit") ? "COMMITTED" : "RELEASED";
      status.put(txn, state);
      send(ex, 200, result(txn, state).put("holdId", holdId).toString(), 0);
    } else if ("POST".equals(m) && path.equals("/core/v1/credits")) {
      status.put(body.path("txnId").asString(), "CREDITED");
      send(ex, 200, result(body.path("txnId").asString(), "CREDITED").toString(), 0);
    } else if ("POST".equals(m) && path.equals("/core/v1/reversals")) {
      status.put(body.path("originalTxnId").asString(), "REVERSED");
      send(ex, 200, result(body.path("txnId").asString(), "REVERSED").toString(), 0);
    } else if ("GET".equals(m) && path.startsWith("/core/v1/transactions/")) {
      String txn = path.substring("/core/v1/transactions/".length());
      ObjectNode s = JSON.createObjectNode().put("txnId", txn).put("status", status.getOrDefault(txn, "NOT_FOUND"));
      holds.forEach((h, t) -> {
        if (t.equals(txn)) {
          s.put("holdId", h);
        }
      });
      s.putArray("entries");
      send(ex, 200, s.toString(), 0);
    } else if ("GET".equals(m) && path.startsWith("/core/v1/balance/")) {
      String wallet = path.substring("/core/v1/balance/".length());
      if (UNKNOWN_WALLET.equals(wallet)) {
        send(ex, 422, error("MW-CORE-WALLET_NOT_FOUND"), 0);
      } else {
        send(ex, 200, "{\"walletId\":\"" + wallet + "\",\"available\":{\"valueMinor\":" + AVAILABLE + ",\"currency\":\"BDT\"},"
            + "\"held\":{\"valueMinor\":0,\"currency\":\"BDT\"},\"total\":{\"valueMinor\":" + AVAILABLE
            + ",\"currency\":\"BDT\"},\"asOf\":\"2026-11-16T08:15:31Z\"}", 0);
      }
    } else {
      send(ex, 404, error("MW-VAL-003"), 0);
    }
  }

  private void validate(HttpExchange ex, JsonNode body) throws IOException {
    String txn = body.path("txnId").asString();
    if (body.path("amount").path("valueMinor").asLong() == REJECT_AMOUNT) {
      status.putIfAbsent(txn, "REJECTED");
      send(ex, 422, error("MW-CORE-INSUFFICIENT_BALANCE"), 0);
      return;
    }
    status.putIfAbsent(txn, "VALIDATED");
    send(ex, 200, result(txn, "VALIDATED").set("fee", JSON.readTree("{\"valueMinor\":500,\"currency\":\"BDT\"}")).toString(), 0);
  }

  private void transfer(HttpExchange ex, JsonNode body) throws IOException {
    String txn = body.path("txnId").asString();
    if (!"POSTED".equals(status.get(txn))) {
      postings.computeIfAbsent(txn, k -> new AtomicInteger()).incrementAndGet();
      status.put(txn, "POSTED");
    }
    long delay = body.path("amount").path("valueMinor").asLong() == SILENT_AMOUNT ? SILENT_DELAY_MS : 0;
    send(ex, 200, result(txn, "POSTED").toString(), delay);
  }

  private static ObjectNode result(String txnId, String state) {
    return JSON.createObjectNode().put("txnId", txnId).put("status", state)
        .put("coreRef", "STUB-" + (txnId.length() >= 8 ? txnId.substring(0, 8) : txnId));
  }

  private static String error(String code) {
    return "{\"code\":\"" + code + "\",\"message\":\"stub\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}";
  }

  private static void send(HttpExchange ex, int code, String body, long delayMs) throws IOException {
    if (delayMs > 0) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] out = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    try {
      ex.sendResponseHeaders(code, out.length);
      ex.getResponseBody().write(out);
    } catch (IOException e) {
      // the client gave up waiting (read timeout)
    } finally {
      ex.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
