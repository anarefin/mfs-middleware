package mfs.mw.data.export.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import mfs.mw.data.export.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A fact older than the online window is gzipped, removed, and readable again (ticket 128). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "mw.archive.online.transactions=1d")
@Import(TestInfrastructure.class)
class ArchivalIT {

  static final java.nio.file.Path DIR;

  static {
    try {
      DIR = Files.createTempDirectory("dw-archive");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void archive(DynamicPropertyRegistry registry) {
    registry.add("mw.archive.directory", () -> DIR.toString());
  }

  @LocalServerPort
  int port;

  private final JdbcTemplate jdbc;
  private final ArchivalJob job;

  ArchivalIT(JdbcTemplate jdbc, ArchivalJob job) {
    this.jdbc = jdbc;
    this.job = job;
  }

  @Test
  void anOldTransactionLeavesTheOnlineTableAndComesBackFromTheArchive() throws Exception {
    LocalDate day = LocalDate.now(ZoneId.of("Asia/Dhaka")).minusDays(5);
    jdbc.update(
        """
        insert into txn_fact (txn_id, txn_type, status, amount_minor, currency, channel,
          payer_token, payee_token, created_at, completed_at, business_date)
        values ('txn-old', 'P2P', 'COMPLETED', 100, 'BDT', 'APP', 'MSISDN_PAYER', 'MSISDN_PAYEE',
          now(), now(), ?)
        """,
        day);
    assertThat(job.archive()).isGreaterThan(0);
    Integer left = jdbc.queryForObject("select count(*) from txn_fact where txn_id = 'txn-old'", Integer.class);
    assertThat(left).isZero();
    HttpResponse<String> res = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/archive/transactions/" + day))
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("txn-old");
  }
}
