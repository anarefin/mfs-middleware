package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import com.prowidesoftware.swift.model.mt.mt9xx.MT940;
import com.prowidesoftware.swift.model.mt.mt9xx.MT942;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.simulators.client.SftpClient;
import mfs.mw.simulators.config.FileMockProperties;
import mfs.mw.simulators.scheduler.TrustStatementJob;
import mfs.mw.simulators.util.Hashing;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The file mocks of ticket 123 against a real SFTP host (the stack's partner-sftp image) and a stub mw-core-mock:
 * an AML transaction file uploaded to {@code aml/in} gets its response file in {@code aml/out}; the statement job
 * writes an MT940 and an MT942 that Prowide parses, from the core's ledger read with a client-credentials token;
 * the DW validator flags a drop whose control file has a wrong row count; and every file is in the journal.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class SftpMocksIT {

  private static final String PASSWORD = "fake-sftp-password-for-tests";

  @SuppressWarnings("resource")
  static final GenericContainer<?> SFTP =
      new GenericContainer<>(
              "atmoz/sftp:alpine@sha256:c5fb9e4beb8b7054a4c0b65c5e11df47cc20da95ad6002e88aecff62bab06372")
          .withCommand("bits:" + PASSWORD + ":1001::aml/in,aml/out,trustbank/out,dw/in")
          .withExposedPorts(22)
          .waitingFor(Wait.forListeningPort());

  private static final HttpServer CORE = core();
  private static final List<String> CORE_CALLS = new CopyOnWriteArrayList<>();

  private static final String LEDGER = """
      {"walletId":"SETTLEMENT","from":"x","to":"y","page":0,"hasMore":false,"entries":[
       {"txnId":"0196a1b2-0000-7000-8000-00000000000a","walletId":"SETTLEMENT","direction":"CREDIT","kind":"TRANSFER",
        "amount":{"valueMinor":25000,"currency":"BDT"},"balanceAfter":{"valueMinor":125000,"currency":"BDT"},
        "at":"2026-11-20T03:00:00Z"},
       {"txnId":"0196a1b2-0000-7000-8000-00000000000b","walletId":"SETTLEMENT","direction":"DEBIT","kind":"TRANSFER",
        "amount":{"valueMinor":5000,"currency":"BDT"},"balanceAfter":{"valueMinor":120000,"currency":"BDT"},
        "at":"2026-11-20T04:00:00Z"}]}""";

  private static HttpServer core() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/token", ex -> answer(ex, "{\"access_token\":\"fake-token\",\"expires_in\":300}"));
      s.createContext("/core/v1/statements", ex -> {
        CORE_CALLS.add(ex.getRequestURI().getQuery() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
        answer(ex, LEDGER);
      });
      s.createContext("/core-mock/v1/wallets/SETTLEMENT", ex -> answer(ex, "{\"balanceMinor\":120000}"));
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void answer(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    SFTP.start();
    r.add("sim.files.enabled", () -> "true");
    // the jobs are called by the test, never by their schedule
    r.add("sim.files.aml.poll", () -> "1h");
    r.add("sim.files.dw.poll", () -> "1h");
    r.add("sim.files.trustbank.intraday", () -> "1h");
    r.add("sim.files.trustbank.cutoff-cron", () -> "-");
    r.add("sim.files.sftp.host", SFTP::getHost);
    r.add("sim.files.sftp.port", () -> SFTP.getMappedPort(22));
    r.add("sim.files.sftp.password", () -> PASSWORD);
    String core = "http://127.0.0.1:" + CORE.getAddress().getPort();
    r.add("sim.files.trustbank.core.base-url", () -> core);
    r.add("sim.files.trustbank.core.token-uri", () -> core + "/token");
    r.add("sim.files.trustbank.core.client-secret", () -> "fake-client-secret");
  }

  @AfterAll
  static void stop() {
    CORE.stop(0);
  }

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private final SftpClient middleware;
  private final TrustStatementJob statements;

  @LocalServerPort int port;

  @Autowired
  SftpMocksIT(FileMockProperties props, TrustStatementJob statements) {
    this.middleware = new SftpClient(props);
    this.statements = statements;
  }

  @BeforeEach
  void fresh() throws Exception {
    send("POST", "/mock-control/reset", null);
    CORE_CALLS.clear();
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String amlFile(String... rows) {
    StringBuilder body = new StringBuilder("H|AML_TXN|1|F-1|2026-11-20T10:00:00+06:00\n");
    long total = 0;
    for (String r : rows) {
      body.append(r).append('\n');
      total += Long.parseLong(r.split("\\|")[6]);
    }
    return body + "T|" + rows.length + "|" + total + "|"
        + Hashing.sha256Hex(body.toString().getBytes(StandardCharsets.UTF_8)) + "\n";
  }

  @Test
  void anUploadedTransactionFileGetsItsResponseFile() throws Exception {
    String file = amlFile(
        "D|0196a1b2-0000-7000-8000-000000000001|P2P|APP|WALLET:01712345678|WALLET:01811116666|1000|2026-11-20T09:00:00+06:00");
    middleware.write("aml/in/AML_TXN_2026112010.txt", file.getBytes(StandardCharsets.UTF_8));

    HttpResponse<String> res = send("POST", "/mock-control/aml/run", null);

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    String answer = new String(middleware.read("aml/out/AML_RESP_2026112010.txt"), StandardCharsets.UTF_8);
    assertThat(answer).contains("BLOCK|0196a1b2-0000-7000-8000-000000000001|01811116666|BLOCK_SUFFIX|").endsWith("T|1|0|0|1\n");
    JsonNode entries = json.readTree(res.body()).path("entries");
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).path("method").asString()).isEqualTo("READ");
    assertThat(entries.get(1).path("method").asString()).isEqualTo("WRITE");
    assertThat(entries.get(1).path("headers").path("sha256").asString())
        .isEqualTo(Hashing.sha256Hex(answer.getBytes(StandardCharsets.UTF_8)));
    assertThat(json.readTree(send("POST", "/mock-control/aml/run", null).body()).path("entries"))
        .as("a file is answered once").isEmpty();
  }

  @Test
  void theStatementJobWritesAnMt940AndAnMt942FromTheLedger() throws Exception {
    HttpResponse<String> res = send("POST", "/mock-control/trustbank/run", "{\"operation\":\"mt940\",\"date\":\"2026-11-20\"}");
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);

    MT940 mt940 = MT940.parse(new String(middleware.read("trustbank/out/MT940_0011223344556_20261120.fin"), StandardCharsets.UTF_8));
    assertThat(mt940.getField60F().getAmountAsBigDecimal()).isEqualByComparingTo("1000.00");
    assertThat(mt940.getField62F().getAmountAsBigDecimal()).isEqualByComparingTo("1200.00");
    assertThat(mt940.getField61()).hasSize(2);
    assertThat(CORE_CALLS).first().asString().contains("walletId=SETTLEMENT").contains("from=2026-11-19T18:00:00Z&to=2026-11-20T18:00:00Z")
        .endsWith("auth=Bearer fake-token");

    statements.intraday();
    List<String> out = middleware.list("trustbank/out");
    String mt942 = out.stream().filter(n -> n.startsWith("MT942_")).findFirst().orElseThrow();
    assertThat(MT942.parse(new String(middleware.read("trustbank/out/" + mt942), StandardCharsets.UTF_8)).getField61()).hasSize(2);
    assertThat(json.readTree(send("GET", "/mock-control/trustbank/journal", null).body())).hasSize(2);
  }

  @Test
  void aMismatchScenarioReachesTheStatementOnTheFileHost() throws Exception {
    assertThat(send("POST", "/mock-control/trustbank/scenarios",
        "{\"operation\":\"mt940\",\"behaviour\":\"INJECTED_MISMATCH\",\"code\":\"MISSING_IN_PARTNER\",\"count\":1,\"nextN\":1}")
        .statusCode()).isEqualTo(201);

    send("POST", "/mock-control/trustbank/run", "{\"date\":\"2026-11-20\"}");

    MT940 mt940 = MT940.parse(new String(middleware.read("trustbank/out/MT940_0011223344556_20261120.fin"), StandardCharsets.UTF_8));
    assertThat(mt940.getField61()).hasSize(1);
  }

  @Test
  void theDwValidatorFlagsAWrongRowCount() throws Exception {
    byte[] csv = ("txn_id,type,status,amount_minor,currency,channel,payer_token,payee_token,created_at,completed_at\n"
        + "T1,P2P,COMPLETED,100,BDT,APP,tok1,tok2,2026-11-20T09:00:00+06:00,2026-11-20T09:00:01+06:00\n")
        .getBytes(StandardCharsets.UTF_8);
    middleware.write("dw/in/transactions_2026-11-20.csv", csv);
    middleware.write("dw/in/control_2026-11-20.json", ("{\"date\":\"2026-11-20\",\"files\":[{\"name\":"
        + "\"transactions_2026-11-20.csv\",\"rows\":5,\"sha256\":\"" + Hashing.sha256Hex(csv) + "\"}]}")
        .getBytes(StandardCharsets.UTF_8));

    HttpResponse<String> res = send("POST", "/mock-control/dw/run", null);

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    JsonNode journal = json.readTree(send("GET", "/mock-control/dw/journal?path=control_", null).body());
    assertThat(journal).hasSize(1);
    assertThat(journal.get(0).path("headers").path("verdict").asString()).isEqualTo("REJECTED");
    assertThat(journal.get(0).path("headers").path("reasons").asString())
        .isEqualTo("ROW_COUNT transactions_2026-11-20.csv: control 5, file 1");
  }

  @Test
  void aFileDroppedThroughMockControlLandsInTheInbox() throws Exception {
    String file = amlFile(
        "D|0196a1b2-0000-7000-8000-000000000009|P2P|APP|WALLET:01711000777|WALLET:01812345678|1000|2026-11-20T09:00:00+06:00");
    HttpResponse<String> drop = send("POST", "/mock-control/aml/files",
        json.writeValueAsString(java.util.Map.of("name", "AML_TXN_drop.txt", "content", file)));

    assertThat(drop.statusCode()).as(drop.body()).isEqualTo(201);
    assertThat(json.readTree(drop.body()).path("path").asString()).isEqualTo("aml/in/AML_TXN_drop.txt");
    assertThat(json.readTree(drop.body()).path("sha256").asString())
        .isEqualTo(Hashing.sha256Hex(file.getBytes(StandardCharsets.UTF_8)));
    send("POST", "/mock-control/aml/run", null);
    assertThat(new String(middleware.read("aml/out/AML_RESP_drop.txt"), StandardCharsets.UTF_8)).contains("HOLD|");
    assertThat(send("POST", "/mock-control/trustbank/files", "{\"name\":\"x.txt\",\"content\":\"x\"}").statusCode())
        .as("the trust bank has no inbox").isEqualTo(400);
    assertThat(send("POST", "/mock-control/aml/files", "{\"name\":\"../x\",\"content\":\"x\"}").statusCode()).isEqualTo(400);
  }

  @Test
  void aPartyThatIsNoFileMockCannotRun() throws Exception {
    assertThat(send("POST", "/mock-control/ocs/run", null).statusCode()).isEqualTo(400);
    assertThat(send("POST", "/mock-control/nope/run", null).statusCode()).isEqualTo(404);
    assertThat(send("POST", "/mock-control/trustbank/run", "{\"operation\":\"respond\"}").statusCode()).isEqualTo(400);
  }
}
