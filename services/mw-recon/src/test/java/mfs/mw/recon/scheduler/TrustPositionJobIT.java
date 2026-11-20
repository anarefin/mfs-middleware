package mfs.mw.recon.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.recon.TestInfrastructure;
import mfs.mw.recon.dto.TrustPosition;
import mfs.mw.recon.service.ReconService;
import mfs.mw.recon.service.TrustPositionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * REC-07 end to end in the service (ticket 127c): an MT940 closing balance is in, the job asks the core (a stand-in
 * serving the contract's {@code EmoneyTotal}) with a realm services token and stores the position; a gap is one
 * {@code AMOUNT_MISMATCH} / {@code TRUST_POSITION} exception.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class TrustPositionJobIT {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 14);

  private static final String FIN = """
      {1:F01BITSBDDHXXXX0000000000}{2:I940TRUSTBDDXXXXN}{4:
      :20:RPT127C
      :25:0011223344556
      :28C:279/1
      :60F:C260914BDT1100,00
      :61:2609140914D100,00NTRF0000800000127002//TB2
      FEE
      :86:0196a1b2-0000-7000-8000-000000127c02
      :62F:C260914BDT1000,00
      -}""";

  private static final List<String> SEEN = new CopyOnWriteArrayList<>();
  private static volatile long coreTotal = 100_000;
  private static final HttpServer CORE = start();

  private final TrustPositionJob job;
  private final TrustPositionService positions;
  private final ReconService recon;
  private final JdbcTemplate jdbc;

  @Autowired
  TrustPositionJobIT(TrustPositionJob job, TrustPositionService positions, ReconService recon, JdbcTemplate jdbc) {
    this.job = job;
    this.positions = positions;
    this.recon = recon;
    this.jdbc = jdbc;
  }

  @DynamicPropertySource
  static void core(DynamicPropertyRegistry r) {
    String base = "http://127.0.0.1:" + CORE.getAddress().getPort();
    r.add("mw.recon.core.base-url", () -> base);
    r.add("mw.recon.core.token-uri", () -> base + "/token");
    r.add("mw.recon.core.client-secret", () -> "fake-recon-secret");
  }

  @AfterAll
  static void stop() {
    CORE.stop(0);
  }

  @Test
  void theJobComparesTheCoreTotalWithTheClosingBalanceAndRaisesAGap() {
    assertThat(job.runFor(DAY)).as("no statement yet: nothing asked").isNull();
    assertThat(SEEN).isEmpty();

    recon.ingestStatement("MT940_127c.fin", FIN.getBytes(StandardCharsets.UTF_8), true);
    assertThat(positions.latestClosingDate()).isNotNull();
    assertThat(positions.closingKnown(DAY)).isTrue();

    coreTotal = 100_000;
    TrustPosition even = job.runFor(DAY);
    assertThat(even.differenceMinor()).isZero();
    assertThat(even.exceptionId()).isNull();
    assertThat(SEEN).contains("core:Bearer tok-recon");

    coreTotal = 100_250;
    TrustPosition gap = job.runFor(DAY);
    assertThat(gap.differenceMinor()).isEqualTo(-250L);
    assertThat(jdbc.queryForObject("select class || '/' || detail from recon_exception where id = ?", String.class,
        gap.exceptionId())).isEqualTo("AMOUNT_MISMATCH/TRUST_POSITION");
    assertThat(jdbc.queryForObject("select difference_minor from trust_position where business_date = ?", Long.class,
        DAY)).isEqualTo(-250L);
  }

  @Test
  void aQuietDaysStatementIsKeyedByIts62fDateAndAForwardValueDateDoesNotMoveIt() {
    LocalDate quiet = LocalDate.of(2026, 9, 20);
    recon.ingestStatement("MT940_quiet.fin", """
        {1:F01BITSBDDHXXXX0000000000}{2:I940TRUSTBDDXXXXN}{4:
        :20:RPTQUIET
        :25:0011223344556
        :28C:285/1
        :60F:C260920BDT700,00
        :62F:C260920BDT700,00
        -}""".getBytes(StandardCharsets.UTF_8), true);
    assertThat(positions.closingKnown(quiet)).as("no :61: lines: still the 62F date, not today").isTrue();
    assertThat(positions.compute(quiet, 70_000).differenceMinor()).isZero();

    LocalDate day = LocalDate.of(2026, 9, 21);
    recon.ingestStatement("MT940_forward.fin", """
        {1:F01BITSBDDHXXXX0000000000}{2:I940TRUSTBDDXXXXN}{4:
        :20:RPTFWD
        :25:0011223344556
        :28C:286/1
        :60F:C260921BDT700,00
        :61:2609230923D100,00NTRF0000800000127003//TB3
        FEE
        :86:0196a1b2-0000-7000-8000-000000127c03
        :62F:C260921BDT600,00
        -}""".getBytes(StandardCharsets.UTF_8), true);
    assertThat(positions.closingKnown(day)).isTrue();
    assertThat(positions.closingKnown(LocalDate.of(2026, 9, 23))).isFalse();
  }

  private static HttpServer start() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/token", ex -> reply(ex, "{\"access_token\":\"tok-recon\",\"expires_in\":300}"));
      s.createContext("/core/v1/reports/emoney-total", ex -> {
        SEEN.add("core:" + ex.getRequestHeaders().getFirst("Authorization"));
        reply(ex, """
            {"asOf":"2026-09-14T18:45:00Z","total":{"valueMinor":%d,"currency":"BDT"},"walletCount":1,"byType":[]}"""
            .formatted(coreTotal));
      });
      s.start();
      return s;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }
}
