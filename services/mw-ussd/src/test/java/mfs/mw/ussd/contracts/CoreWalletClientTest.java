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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.ussd.client.CoreWalletClient;
import mfs.mw.ussd.client.ServiceTokenClient;
import mfs.mw.ussd.config.AuthClientProperties;
import mfs.mw.ussd.config.CoreClientProperties;
import mfs.mw.ussd.service.AccountScreens;
import mfs.mw.ussd.service.ChargeQuote;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WAL-04, WAL-12 and WAL-13 as {@link CoreWalletClient} calls them: the charge request matches
 * ChargesRequest, a statement keeps the latest movements, and a remittance reference is a txn id.
 */
class CoreWalletClientTest {

  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
  static final String TXN = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String CHARGES = """
      {"txnType":"P2P","amount":{"valueMinor":1000,"currency":"BDT"},
       "fee":{"valueMinor":500,"currency":"BDT"},"vat":{"valueMinor":65,"currency":"BDT"},
       "commission":{"valueMinor":0,"currency":"BDT"},"total":{"valueMinor":1500,"currency":"BDT"}}""";
  static final String STATEMENT = """
      {"walletId":"01712345678","from":"2026-10-05T10:00:00Z","to":"2026-11-04T10:00:00Z",
       "page":0,"hasMore":false,"entries":[
        {"txnId":"t1","walletId":"01712345678","direction":"DEBIT","kind":"TRANSFER",
         "amount":{"valueMinor":1000,"currency":"BDT"},"at":"2026-11-01T00:00:00Z"},
        {"txnId":"t1","walletId":"01712345678","direction":"DEBIT","kind":"FEE",
         "amount":{"valueMinor":500,"currency":"BDT"},"at":"2026-11-01T00:00:00Z"},
        {"txnId":"t2","walletId":"01712345678","direction":"CREDIT","kind":"TRANSFER",
         "amount":{"valueMinor":2500,"currency":"BDT"},"at":"2026-11-03T00:00:00Z"}]}""";

  HttpServer server;
  AtomicReference<String> chargeBody = new AtomicReference<>();
  AtomicInteger reads = new AtomicInteger();
  /** When set, {@code GET /statements} answers this page instead of {@link #STATEMENT}. */
  AtomicReference<java.util.function.IntFunction<String>> statementPages = new AtomicReference<>();
  List<Integer> statementPageQueries = new ArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", this::token);
    server.createContext("/core/v1/charges/calculate", this::charges);
    server.createContext("/core/v1/statements", this::statement);
    server.createContext("/core/v1/transactions", this::transaction);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void aQuotePostsChargesRequestAndReadsTheFee() throws IOException {
    CoreWalletClient client = client();
    ChargeQuote.Quoted quoted = client.quote("P2P", 1000, "CUSTOMER");

    assertThat(quoted).isEqualTo(new ChargeQuote.Quoted(500, 1500));
    assertThat(OpenApiSchemas.component(ApiContractsTest.CORE_API, "ChargesRequest")
        .validate(ApiContractsTest.JSON.readTree(chargeBody.get()))).isEmpty();
    assertThat(OpenApiSchemas.component(ApiContractsTest.CORE_API, "Charges")
        .validate(ApiContractsTest.JSON.readTree(CHARGES))).isEmpty();
  }

  @Test
  void aStatementKeepsTheLatestMovementsNewestFirst() {
    assertThat(client().statement("01712345678", "en"))
        .isEqualTo("Recent\n03/11 Cr 25.00\n01/11 Dr 10.00");
    assertThat(reads.get()).isEqualTo(1);
  }

  /**
   * A 30-day history longer than three pages of 100 is oldest-first. The screen is the latest five
   * movements, not the tail of the first three pages.
   */
  @Test
  void aBusierMonthReturnsTheLatestMovements() {
    statementPages.set(page -> {
      if (page < 3) {
        return statementPage(page, true, movement("2026-10-0" + (6 + page) + "T00:00:00Z", "DEBIT", 100));
      }
      return statementPage(page, false, String.join(",",
          movement("2026-11-04T01:00:00Z", "CREDIT", 1_100),
          movement("2026-11-04T02:00:00Z", "CREDIT", 1_200),
          movement("2026-11-04T03:00:00Z", "CREDIT", 1_300),
          movement("2026-11-04T04:00:00Z", "CREDIT", 1_400),
          movement("2026-11-04T05:00:00Z", "CREDIT", 1_500)));
    });

    assertThat(client().statement("01712345678", "en")).isEqualTo("""
        Recent
        04/11 Cr 15.00
        04/11 Cr 14.00
        04/11 Cr 13.00
        04/11 Cr 12.00
        04/11 Cr 11.00""");
    assertThat(statementPageQueries).contains(3);
  }

  @Test
  void aRemittanceReferenceIsAnUpayTxnIdOrThereIsNone() {
    CoreWalletClient client = client();
    assertThat(client.remitStatus("ABC12345", "bn")).isEqualTo(AccountScreens.noRemit("bn"));
    assertThat(reads.get()).isZero();

    assertThat(client.remitStatus(TXN, "en")).isEqualTo("Status POSTED");
    assertThat(client.remitStatus("019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0e", "en"))
        .isEqualTo(AccountScreens.noRemit("en"));
  }

  private CoreWalletClient client() {
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    ServiceTokenClient tokens = new ServiceTokenClient(
        new AuthClientProperties(base, URI.create(base + "/token"), "mw-ussd", "secret",
            Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMinutes(5)),
        ApiContractsTest.JSON, Clock.systemUTC());
    return new CoreWalletClient(
        new CoreClientProperties("http", base, Duration.ofSeconds(1), Duration.ofSeconds(2)),
        tokens, ApiContractsTest.JSON, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void token(HttpExchange ex) throws IOException {
    write(ex, 200, "{\"access_token\":\"tok\",\"expires_in\":300}");
  }

  private void charges(HttpExchange ex) throws IOException {
    chargeBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    write(ex, 200, CHARGES);
  }

  private void statement(HttpExchange ex) throws IOException {
    reads.incrementAndGet();
    int page = 0;
    String query = ex.getRequestURI().getQuery();
    if (query != null) {
      for (String part : query.split("&")) {
        if (part.startsWith("page=")) {
          page = Integer.parseInt(part.substring("page=".length()));
        }
      }
    }
    statementPageQueries.add(page);
    var pages = statementPages.get();
    write(ex, 200, pages == null ? STATEMENT : pages.apply(page));
  }

  private static String statementPage(int page, boolean hasMore, String entries) {
    return """
        {"walletId":"01712345678","from":"2026-10-05T10:00:00Z","to":"2026-11-04T10:00:00Z",
         "page":%d,"hasMore":%s,"entries":[%s]}""".formatted(page, hasMore, entries);
  }

  private static String movement(String at, String direction, long minor) {
    return """
        {"txnId":"t","walletId":"01712345678","direction":"%s","kind":"TRANSFER",
         "amount":{"valueMinor":%d,"currency":"BDT"},"at":"%s"}""".formatted(direction, minor, at);
  }

  private void transaction(HttpExchange ex) throws IOException {
    reads.incrementAndGet();
    String id = ex.getRequestURI().getPath().substring("/core/v1/transactions/".length());
    String status = TXN.equals(id) ? "POSTED" : "NOT_FOUND";
    write(ex, 200, "{\"txnId\":\"" + id + "\",\"status\":\"" + status + "\",\"entries\":[]}");
  }

  private static void write(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }
}
