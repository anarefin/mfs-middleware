package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.portal.api.MutableClock;
import mfs.mw.portal.api.TestInfrastructure;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.dto.PortalUser;
import mfs.mw.portal.api.exception.MwException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The org wallet balance check of a bulk batch (ticket 129c, FRS CH-POR-04) through its service
 * seams, against PostgreSQL and a stub of the core wallet API ({@code GET /core/v1/balance/{walletId}},
 * this service's client-credentials token): at approval and again when the dispatcher starts the
 * batch, a balance below the batch's amount blocks it (BLOCKED_FUNDS, the shortfall, {@code
 * MW-CORE-INSUFFICIENT_BALANCE}) and no child transaction is created.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.outbox.relay.enabled=false",
      "mw.portal-api.consumers.enabled=false",
      "mw.portal-api.bulk.dispatch.enabled=false",
      "mw.portal-api.bulk.dispatch.rate-per-second=100",
      "mw.portal-api.bulk.dispatch.period=1s",
      "mw.portal-api.core.balance-check=true"
    })
@Import({TestInfrastructure.class, BalanceCheckIT.TestClock.class})
class BalanceCheckIT {

  static final Instant START = Instant.parse("2026-11-04T10:00:00Z");
  static final String TOKEN = "balance-token";
  static final String ORG_WALLET = "01311000001";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(START);
    }
  }

  // --- core stub: the org wallet's available balance in poisha; < 0 = the core is down ---------

  static volatile long available;
  static final List<String> ASKED = new CopyOnWriteArrayList<>();
  static final HttpServer CORE = startStub();

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/core/v1/balance/", BalanceCheckIT::answer);
      s.createContext("/token", ex -> respond(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}"));
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void answer(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String wallet = path.substring(path.lastIndexOf('/') + 1);
    ASKED.add(wallet);
    if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      respond(ex, 401, "{}");
    } else if (available < 0) {
      respond(ex, 503, "{}");
    } else if (!ORG_WALLET.equals(wallet)) {
      respond(ex, 404, "{\"code\":\"MW-CORE-WALLET_NOT_FOUND\",\"message\":\"x\",\"retryable\":false}");
    } else {
      respond(ex, 200, "{\"walletId\":\"" + wallet + "\",\"available\":{\"valueMinor\":" + available
          + ",\"currency\":\"BDT\"},\"held\":{\"valueMinor\":0,\"currency\":\"BDT\"},\"total\":{\"valueMinor\":"
          + available + ",\"currency\":\"BDT\"},\"asOf\":\"2026-11-04T10:00:00Z\"}");
    }
  }

  static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @AfterAll
  static void stopStub() {
    CORE.stop(0);
  }

  @DynamicPropertySource
  static void core(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + CORE.getAddress().getPort();
    registry.add("mw.portal-api.core.base-url", () -> base);
    registry.add("mw.portal-api.orchestrator.auth.token-uri", () -> base + "/token");
  }

  static final PortalUser MAKER = new PortalUser("maker-sub-1", "org-maker01", "ORG001", Set.of("org-maker"));
  static final PortalUser CHECKER = new PortalUser("checker-sub-1", "org-checker01", "ORG001", Set.of("org-checker"));

  private final BatchService batches;
  private final BulkExecutionService execution;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;

  @Autowired
  BalanceCheckIT(BatchService batches, BulkExecutionService execution, MutableClock clock, JdbcTemplate jdbc) {
    this.batches = batches;
    this.execution = execution;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    clock.set(START);
    available = 1_000_000;
    ASKED.clear();
    jdbc.update("delete from outbox");
    jdbc.update("delete from batch_item");
    jdbc.update("delete from batch");
    execution.resetThrottle();
  }

  /** 3 rows of 1.25 taka: 375 poisha, submitted. */
  private String submitted() {
    String csv = "msisdn,amount,reference\n01711000001,1.25,A\n01711000002,1.25,B\n01711000003,1.25,C\n";
    String id = batches.upload(MAKER, csv.getBytes(StandardCharsets.UTF_8)).batchId();
    batches.submit(MAKER, id);
    return id;
  }

  private void drain() {
    for (int i = 0; i < 20; i++) {
      clock.advance(Duration.ofSeconds(1));
      execution.dispatch();
    }
  }

  private int children() {
    return jdbc.queryForObject("select count(*) from outbox where topic = 'mw.txn.request.v1'", Integer.class);
  }

  @Test
  void shortFundsAtApprovalBlockTheBatchWithItsShortfallAndNothingIsSent() {
    String id = submitted();
    available = 300;

    BatchView blocked = batches.approve(CHECKER, id);
    assertThat(blocked.status()).isEqualTo("BLOCKED_FUNDS");
    assertThat(blocked.blockedReason()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(blocked.shortfall().valueMinor()).isEqualTo(75);
    assertThat(ASKED).containsExactly(ORG_WALLET);
    drain();
    assertThat(children()).isZero();

    // the org tops up; the checker approves again and the batch runs
    available = 375;
    BatchView approved = batches.approve(CHECKER, id);
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(approved.shortfall()).isNull();
    drain();
    assertThat(children()).isEqualTo(3);
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING");
  }

  @Test
  void shortFundsAtDispatchStartBlockTheBatchAndNothingIsSent() {
    String id = submitted();
    assertThat(batches.approve(CHECKER, id).status()).isEqualTo("APPROVED");
    available = 100; // spent elsewhere between approval and execution

    drain();
    assertThat(children()).isZero();
    BatchView blocked = batches.get(CHECKER, id);
    assertThat(blocked.status()).isEqualTo("BLOCKED_FUNDS");
    assertThat(blocked.shortfall().valueMinor()).isEqualTo(275);
    assertThat(jdbc.queryForList("select status from batch_item where batch_id = ?::uuid", String.class, id))
        .containsOnly("PENDING");
  }

  @Test
  void anUnavailableCoreRefusesTheApprovalAndHoldsTheStartUntilItAnswers() {
    String id = submitted();
    available = -1;
    MwException refused = null;
    try {
      batches.approve(CHECKER, id);
    } catch (MwException e) {
      refused = e;
    }
    assertThat(refused).isNotNull();
    assertThat(refused.code()).isEqualTo("MW-SYS-041");
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("PENDING_APPROVAL");

    available = 1_000;
    batches.approve(CHECKER, id);
    available = -1;
    drain();
    assertThat(children()).isZero();
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("APPROVED");

    available = 1_000;
    drain();
    assertThat(children()).isEqualTo(3);
  }
}
