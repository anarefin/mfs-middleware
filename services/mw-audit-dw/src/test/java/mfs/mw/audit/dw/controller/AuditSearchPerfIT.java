package mfs.mw.audit.dw.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.sql.DataSource;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.client.MsisdnTokeniser;
import mfs.mw.audit.dw.security.InternalTokens;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ticket 130, FRS AUD-03: every search filter combination answers within 3 s over 30 days of audit
 * trail. Seeds 30 days of 2,000 transactions a day (60,000 transactions in {@code audit.txn_index},
 * four events each: 240,000 rows of {@code audit.event} over two monthly partitions) straight into
 * the tables as the superuser, then times each query over HTTP as mw-admin-api. Tagged {@code
 * slow-wait} (seeding takes a while): runs with {@code -PwithSlow} at the milestone gate.
 */
@Tag("slow-wait")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"mw.audit-dw.consumers.enabled=false", "mw.audit-dw.query.tokenise-msisdn=true"})
@Import({TestInfrastructure.class, AuditSearchPerfIT.Tokens.class})
class AuditSearchPerfIT {

  private static final String FAKE_READER_PASSWORD = "test-only-reader-pw";
  private static final Duration TARGET = Duration.ofSeconds(3);
  private static final String WINDOW = "from=2026-10-04T00:00:00Z&to=2026-11-04T00:00:00Z";

  /** The search token of 01711000042 is the seeded token MSISDN_42 (test only). */
  @TestConfiguration(proxyBeanMethods = false)
  static class Tokens {
    @Bean
    @Primary
    MsisdnTokeniser seededTokens() {
      return values -> values.stream().map(v -> "MSISDN_" + Integer.parseInt(v.substring(5))).toList();
    }
  }

  private final JdbcTemplate admin;

  @LocalServerPort int port;

  @Autowired
  AuditSearchPerfIT(DataSource dataSource) {
    this.admin = new JdbcTemplate(dataSource);
    admin.execute("alter role audit_reader login password '" + FAKE_READER_PASSWORD + "'");
  }

  @BeforeAll
  void seed() {
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head, audit.payload, audit.txn_index");
    admin.execute("select audit.ensure_month_partition('2026-10'), audit.ensure_month_partition('2026-11')");
    admin.execute(
        """
        insert into audit.txn_index (txn_id, channel, txn_type, status, amount_minor, currency, partner_id,
                                     msisdn_tokens, requested_at, updated_at)
        select md5('txn' || i)::uuid,
               (array['CUSTOMER_APP','USSD','AGENT_APP','WEB_CHECKOUT','ORG_PORTAL'])[1 + i % 5],
               (array['P2P','TOPUP','BILL_PAY','CASH_OUT'])[1 + i % 4],
               (array['COMPLETED','COMPLETED','COMPLETED','FAILED','REVERSED','MANUAL_REVIEW'])[1 + i % 6],
               (i * 37) % 2500000, 'BDT',
               (array['ocs','billagg','c2wbank',null])[1 + i % 4],
               array['MSISDN_' || (i % 20000), 'MSISDN_' || ((i + 7) % 20000)],
               timestamptz '2026-10-04T00:00:00Z' + (i * interval '43.2 seconds'),
               timestamptz '2026-10-04T00:00:00Z' + (i * interval '43.2 seconds') + interval '2 seconds'
          from generate_series(0, 59999) i
        """);
    admin.execute(
        """
        insert into audit.event (month, seq, audit_id, recorded_at, at, txn_id, correlation_id, actor_type,
                                 actor_id, action, resource, outcome, service, after, prev_hash, hash, hop,
                                 direction, duration_ms)
        select to_char(t.ts at time zone 'UTC', 'YYYY-MM'),
               row_number() over (partition by to_char(t.ts at time zone 'UTC', 'YYYY-MM') order by t.ts, t.k),
               gen_random_uuid(), t.ts, t.ts, t.txn, 'corr-perf', 'system', t.svc, t.action, 'txn/' || t.txn,
               'SUCCESS', t.svc, jsonb_build_object('status', 'COMPLETED'), '\\x00'::bytea, '\\x00'::bytea,
               t.hop, t.dir, 40
          from (select md5('txn' || i)::uuid txn, k,
                       timestamptz '2026-10-04T00:00:00Z' + (i * interval '43.2 seconds') + (k * interval '300 ms') ts,
                       (array['mw-app-gateway','mw-orchestrator','mw-adapter-telco','mw-orchestrator'])[k + 1] svc,
                       (array['channel.request.completed','txn.state.changed','partner.call.completed','txn.state.changed'])[k + 1] action,
                       (array['CHANNEL_IN','ORCHESTRATOR','PARTNER_CALL','ORCHESTRATOR'])[k + 1] hop,
                       (array['IN',null,'OUT',null])[k + 1] dir
                  from generate_series(0, 59999) i, generate_series(0, 3) k) t
        """);
    admin.execute("analyze audit.event");
    admin.execute("analyze audit.txn_index");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "partner=ocs",
        "channel=USSD",
        "status=FAILED",
        "amountMin=100000&amountMax=200000",
        "msisdn=01711000042",
        "partner=billagg&amountMin=50000&amountMax=1500000",
        "channel=CUSTOMER_APP&status=COMPLETED",
        "partner=c2wbank&channel=AGENT_APP&status=REVERSED&amountMin=1000",
        "status=COMPLETED&amountMax=5000000&offset=5000",
        "action=partner.call.completed&partner=ocs",
        "msisdn=01711000042&status=COMPLETED&channel=CUSTOMER_APP"
      })
  void eachFilterCombinationAnswersWithinThreeSecondsOverThirtyDays(String filters) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/internal/v1/audit/events?" + WINDOW + "&limit=100&" + filters))
            .header("Authorization", InternalTokens.bearer("mw-admin-api"))
            .build();
    HttpClient http = HttpClient.newHttpClient();
    // open the lazy reader pool first, so the timing is the query, not the first connection
    http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/audit/events?limit=1"))
            .header("Authorization", InternalTokens.bearer("mw-admin-api"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    long start = System.nanoTime();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.body()).contains("\"items\":[{");
    assertThat(took).as("%s took %s", filters, took).isLessThanOrEqualTo(TARGET);
  }
}
