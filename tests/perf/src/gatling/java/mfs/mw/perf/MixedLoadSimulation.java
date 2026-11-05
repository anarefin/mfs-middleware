package mfs.mw.perf;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jmesPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.perf.app.AppRequests;
import mfs.mw.perf.app.Device;
import mfs.mw.perf.mix.Flow;
import mfs.mw.perf.report.RunWindow;
import mfs.mw.perf.report.TxnLog;
import mfs.mw.perf.report.TxnRecord;
import mfs.mw.perf.stack.PerfUsers;
import mfs.mw.perf.stack.Provisioning;
import mfs.mw.perf.stack.Stack;
import mfs.mw.perf.ussd.UssdSessions;

/**
 * MVP acceptance criterion 7 (spec WP28): a mix of every MVP flow ({@link Flow}, shares in {@code
 * ScenarioMix}) at the target rate on the single MVP host, open model (arrivals per second, one
 * transaction per virtual user). App flows go through APISIX exactly as the app does (customers
 * realm token, bound device, signed submit, PIN encrypted for the core) and wait for the synchronous
 * answer; a share of them reads the transaction back. USSD P2P sessions are played by the USSD
 * gateway simulator. Every transaction is written to {@code build/reports/perf/txns.csv}; {@code
 * ./gradlew perfOverhead} then subtracts core and simulator time (tests/perf/README.md).
 *
 * <p>Only intended business failures pass the checks ({@link Flow#TOPUP_REFUSED}: 502 {@code
 * MW-PTR-GP-TOPUP_REJECTED}); anything else (a 202 unknown outcome included) is a KO, and the run's
 * assertion is zero KO requests.
 */
public final class MixedLoadSimulation extends Simulation {

  private static final String PIN = "1234";
  private static final String SUBMIT = "/app/v1/transactions";
  private static final String REFUSED_CODE = "MW-PTR-GP-TOPUP_REJECTED";

  private final PerfSettings settings = PerfSettings.fromSystem();
  private final PerfUsers users = PerfUsers.fromSettings();
  private final Path runDir = Path.of(System.getProperty("mw.perf.run-dir", "build/reports/perf"));

  private final AtomicLong nextCustomer = new AtomicLong();
  private final AtomicLong nextAgent = new AtomicLong();
  private final AtomicLong nextMerchant = new AtomicLong();
  private final AtomicLong nextUssd = new AtomicLong();

  private volatile Provisioning.Ready ready;
  private volatile AppRequests requests;
  private volatile TxnLog txnLog;
  private volatile String e2eApiKey;

  {
    HttpProtocolBuilder protocol =
        http.baseUrl(Stack.get().baseUrl().toString())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .disableCaching()
            // one keep-alive pool for all virtual users (apps keep their connection): the run
            // measures the middleware, not a TLS handshake per transaction
            .shareConnections()
            .disableFollowRedirect();
    List<PopulationBuilder> populations =
        settings.mix().flows().stream()
            .map(f -> {
              double rate = settings.mix().ratePerSecond(f, settings.tps());
              return scenario(f.name())
                  .exec(f == Flow.USSD_P2P ? ussd() : app(f))
                  .injectOpen(
                      rampUsersPerSec(0).to(rate).during(settings.ramp()),
                      constantUsersPerSec(rate).during(settings.steady()));
            })
            .toList();
    setUp(populations)
        .protocols(protocol)
        .assertions(global().failedRequests().count().is(0L));
  }

  @Override
  public void before() {
    ready = Provisioning.prepare(users, settings.setupParallelism());
    requests = new AppRequests(ready.pin(), PIN);
    e2eApiKey = Stack.get().devCredential("MW_E2E_API_KEY");
    txnLog = new TxnLog(runDir);
    Instant from = Instant.now().plus(settings.ramp());
    txnLog.window(new RunWindow(from, from.plus(settings.steady()), settings.targetTps(), settings.targetP95Ms()));
  }

  @Override
  public void after() {
    if (txnLog != null) {
      txnLog.close();
    }
    if (ready != null) {
      ready.tokens().close();
    }
  }

  // --- app flows -------------------------------------------------------------------------------

  private ChainBuilder app(Flow flow) {
    ChainBuilder chain = exec(session -> pickParties(flow, session));
    if (flow == Flow.BILL_PAY) {
      chain = chain
          .exec(session -> session.set("fetchBody", AppRequests.fetchBill(session.getString("bill"))))
          .exec(withAuth(http("BILL_FETCH").post("app/v1/billers/" + AppRequests.BILLER + "/fetch-bill"))
              .header("X-Correlation-Id", session -> "perf-" + UUID.randomUUID())
              .body(StringBody("#{fetchBody}"))
              .check(status().is(200), jmesPath("amount.valueMinor").ofLong().saveAs("due")));
    }
    boolean refused = flow.intendedFailure();
    return chain
        .exec(session -> sign(flow, session))
        .exec(session -> session.set("t0", System.currentTimeMillis()))
        .exec(withAuth(http(flow.name()).post(SUBMIT))
            .header("X-Timestamp", "#{ts}")
            .header("X-Nonce", "#{nonce}")
            .header("X-Signature", "#{signature}")
            .header("Idempotency-Key", session -> "perf-" + UUID.randomUUID())
            .header("X-Correlation-Id", session -> "perf-" + UUID.randomUUID())
            .body(StringBody("#{body}"))
            .check(
                status().saveAs("httpStatus"),
                jmesPath("txnId").optional().saveAs("txnId"),
                jmesPath("code").optional().saveAs("code"),
                status().is(refused ? 502 : 200))
            .check(refused ? jmesPath("code").is(REFUSED_CODE) : jmesPath("status").is("COMPLETED")))
        .exec(session -> record(flow, session))
        .exec(doIf(session -> session.contains("txnId") && ThreadLocalRandom.current().nextDouble() < settings.statusShare())
            .then(exec(withAuth(http("STATUS").get("app/v1/transactions/#{txnId}")).check(status().is(200)))));
  }

  private HttpRequestActionBuilder withAuth(HttpRequestActionBuilder request) {
    return request.header("Authorization", "Bearer #{token}").header("X-Device-Id", "#{deviceId}");
  }

  /** Payer, counterparty and (BILL_PAY) bill of one transaction; token and device of the payer. */
  private Session pickParties(Flow flow, Session session) {
    List<PerfUsers.User> customers = users.customers();
    long c = nextCustomer.getAndIncrement();
    PerfUsers.User customer = customers.get((int) (c % customers.size()));
    PerfUsers.User payer = customer;
    String counterparty;
    switch (flow) {
      case P2P -> counterparty = customers.get((int) ((c + 1) % customers.size())).msisdn();
      case CASH_IN -> {
        payer = users.agents().get((int) (nextAgent.getAndIncrement() % users.agents().size()));
        counterparty = customer.msisdn();
      }
      case CASH_OUT -> counterparty = users.agents().get((int) (nextAgent.getAndIncrement() % users.agents().size())).msisdn();
      case MERCHANT_PAY ->
          counterparty = users.merchantWallets().get((int) (nextMerchant.getAndIncrement() % users.merchantWallets().size()));
      case TOPUP -> counterparty = customer.msisdn();
      case TOPUP_REFUSED -> counterparty = AppRequests.REFUSED_TOPUP_MSISDN;
      case BILL_PAY -> counterparty = AppRequests.billNumber(ThreadLocalRandom.current());
      case ADD_MONEY_BANK -> counterparty = AppRequests.bankAccount(customer.msisdn());
      default -> throw new IllegalStateException("not an app flow: " + flow);
    }
    Device device = ready.devices().get(payer.username());
    return session
        .set("payer", payer.username())
        .set("counterparty", counterparty)
        .set("bill", counterparty)
        .set("token", ready.tokens().get(payer.username()))
        .set("deviceId", device.id());
  }

  /** The exact body and its signature headers, by the payer's device. */
  private Session sign(Flow flow, Session session) {
    String to = session.getString("counterparty");
    String body =
        switch (flow) {
          case P2P -> requests.p2p(to);
          case CASH_IN -> requests.cashIn(to);
          case CASH_OUT -> requests.cashOut(to);
          case MERCHANT_PAY -> requests.merchantPay(to);
          case TOPUP, TOPUP_REFUSED -> requests.topUp(to);
          // the fetched amount due; the simulator's rule if the fetch failed (already a KO)
          case BILL_PAY -> requests.billPay(to, session.contains("due") ? session.getLong("due") : AppRequests.dueMinor(to));
          case ADD_MONEY_BANK -> requests.addMoneyBank(to);
          default -> throw new IllegalStateException("not an app flow: " + flow);
        };
    Device device = ready.devices().get(session.getString("payer"));
    Map<String, String> h = device.signedHeaders(session.getString("token"), "POST", SUBMIT, body);
    return session
        .set("body", body)
        .set("ts", h.get("X-Timestamp"))
        .set("nonce", h.get("X-Nonce"))
        .set("signature", h.get("X-Signature"));
  }

  // --- USSD ------------------------------------------------------------------------------------

  private ChainBuilder ussd() {
    return exec(session -> {
          List<String> wallets = users.ussdWallets();
          long n = nextUssd.getAndIncrement();
          String to = users.customers().get((int) (n % users.customers().size())).msisdn();
          return session
              .set("ussdBody", UssdSessions.p2p(wallets.get((int) (n % wallets.size())), to, PIN))
              .set("t0", System.currentTimeMillis());
        })
        .exec(http(Flow.USSD_P2P.name())
            .post(UssdSessions.PATH)
            .header("apikey", session -> e2eApiKey)
            .body(StringBody("#{ussdBody}"))
            .check(
                status().saveAs("httpStatus"),
                jmesPath("screens[-1].message").optional().saveAs("lastScreen"),
                status().is(200),
                jmesPath("ended").ofBoolean().is(true),
                jmesPath("screens[-1].message").transform(UssdSessions::sent).is(true)))
        .exec(session -> {
          String last = session.contains("lastScreen") ? session.getString("lastScreen") : null;
          return log(Flow.USSD_P2P, session, UssdSessions.txnId(last), null);
        });
  }

  // --- transaction log -------------------------------------------------------------------------

  private Session record(Flow flow, Session session) {
    return log(
        flow,
        session,
        session.contains("txnId") ? session.getString("txnId") : null,
        session.contains("code") ? session.getString("code") : null);
  }

  private Session log(Flow flow, Session session, String txnId, String code) {
    long t0 = session.getLong("t0");
    int httpStatus = session.contains("httpStatus") ? session.getInt("httpStatus") : 0;
    txnLog.append(new TxnRecord(
        flow, txnId, Instant.ofEpochMilli(t0), System.currentTimeMillis() - t0, httpStatus, code, !session.isFailed()));
    return session;
  }
}
