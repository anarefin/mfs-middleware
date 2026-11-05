package mfs.mw.perf.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunFactsTest {

  @Test
  void per_transaction_sums_are_read_from_psql_csv_output() {
    String out = "0b6f1e5e-0000-4000-8000-000000000001,120\n0b6f1e5e-0000-4000-8000-000000000002,7\n\n";

    assertThat(RunFacts.parseSums(out))
        .containsEntry("0b6f1e5e-0000-4000-8000-000000000001", 120L)
        .containsEntry("0b6f1e5e-0000-4000-8000-000000000002", 7L)
        .hasSize(2);
  }

  @Test
  void the_core_query_sums_reply_latency_of_the_core_outbox_rows_since_the_run_started() {
    String sql = RunFacts.coreSql(Instant.parse("2026-11-06T10:00:00Z"));

    assertThat(sql)
        .contains("coremock.outbox")
        .contains("sent_at - created_at")
        .contains("created_at >= '2026-11-06T10:00:00Z'")
        .contains("group by msg_key");
  }

  @Test
  void the_partner_query_sums_the_adapters_call_durations_from_the_audit_trail() {
    String sql = RunFacts.partnerSql(Instant.parse("2026-11-06T10:00:00Z"));

    assertThat(sql)
        .contains("audit.event")
        .contains("action = 'partner.call.completed'")
        .contains("after->>'durationMs'")
        .contains("recorded_at >= '2026-11-06T10:00:00Z'");
  }
}
