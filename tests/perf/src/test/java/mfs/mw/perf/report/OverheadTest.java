package mfs.mw.perf.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.perf.mix.Flow;
import org.junit.jupiter.api.Test;

class OverheadTest {

  private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");
  private static final RunWindow WINDOW = new RunWindow(T0, T0.plusSeconds(10), 100, 150);

  private static TxnRecord ok(Flow flow, String txnId, long startOffsetMs, long e2eMs) {
    return new TxnRecord(flow, txnId, T0.plusMillis(startOffsetMs), e2eMs, 200, null, true);
  }

  @Test
  void overhead_is_end_to_end_time_minus_core_and_partner_time() {
    List<TxnRecord> txns = List.of(ok(Flow.TOPUP, "t1", 0, 400), ok(Flow.P2P, "t2", 10, 90));

    Overhead.Result r =
        Overhead.compute(txns, Map.of("t1", 120L, "t2", 30L), Map.of("t1", 200L), WINDOW);

    // t1: 400 - 120 core - 200 OCS = 80; t2: 90 - 30 core = 60
    assertThat(r.overall().max()).isEqualTo(80);
    assertThat(r.byFlow().get(Flow.P2P).p95()).isEqualTo(60);
    assertThat(r.byFlow().get(Flow.TOPUP).p95()).isEqualTo(80);
  }

  @Test
  void overhead_is_never_negative_when_clocks_disagree() {
    Overhead.Result r = Overhead.compute(List.of(ok(Flow.P2P, "t1", 0, 50)), Map.of("t1", 70L), Map.of(), WINDOW);

    assertThat(r.overall().max()).isZero();
  }

  @Test
  void p95_is_the_nearest_rank_value() {
    List<TxnRecord> txns = new ArrayList<>();
    for (int i = 1; i <= 100; i++) {
      txns.add(ok(Flow.P2P, "t" + i, i, i)); // overheads 1..100 ms, no core time
    }

    Overhead.Result r = Overhead.compute(txns, Map.of(), Map.of(), WINDOW);

    assertThat(r.overall().count()).isEqualTo(100);
    assertThat(r.overall().p50()).isEqualTo(50);
    assertThat(r.overall().p95()).isEqualTo(95);
    assertThat(r.overall().p99()).isEqualTo(99);
  }

  @Test
  void the_rate_counts_only_transactions_started_in_the_steady_window() {
    List<TxnRecord> txns = new ArrayList<>();
    for (int i = 0; i < 1200; i++) {
      txns.add(ok(Flow.P2P, "s" + i, i * 10L - 1000, 20)); // one every 10 ms from T0 - 1 s to T0 + 11 s
    }

    Overhead.Result r = Overhead.compute(txns, Map.of(), Map.of(), WINDOW);

    // 1,000 of them start in the 10 s window
    assertThat(r.achievedTps()).isEqualTo(100.0);
    assertThat(r.meetsTarget()).isTrue();
  }

  @Test
  void the_target_is_missed_when_the_rate_or_the_p95_falls_short() {
    List<TxnRecord> slow = new ArrayList<>();
    List<TxnRecord> few = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      slow.add(ok(Flow.P2P, "s" + i, i * 10L, 150)); // p95 exactly 150: not under 150
      if (i % 2 == 0) {
        few.add(ok(Flow.P2P, "f" + i, i * 10L, 20)); // 50 TPS
      }
    }

    assertThat(Overhead.compute(slow, Map.of(), Map.of(), WINDOW).meetsTarget()).isFalse();
    assertThat(Overhead.compute(few, Map.of(), Map.of(), WINDOW).meetsTarget()).isFalse();
  }

  @Test
  void unexpected_failures_are_counted_and_fail_the_target_while_intended_ones_do_not() {
    List<TxnRecord> txns = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      txns.add(ok(Flow.P2P, "s" + i, i * 10L, 20));
    }
    txns.add(new TxnRecord(Flow.TOPUP_REFUSED, "r1", T0, 30, 502, "MW-PTR-GP-TOPUP_REJECTED", true));
    Overhead.Result intended = Overhead.compute(txns, Map.of(), Map.of(), WINDOW);
    txns.add(new TxnRecord(Flow.P2P, null, T0, 25_000, 202, null, false));
    Overhead.Result unexpected = Overhead.compute(txns, Map.of(), Map.of(), WINDOW);

    assertThat(intended.unexpectedFailures()).isZero();
    assertThat(intended.meetsTarget()).isTrue();
    assertThat(unexpected.unexpectedFailures()).isEqualTo(1);
    assertThat(unexpected.meetsTarget()).isFalse();
  }

  @Test
  void ussd_sessions_are_reported_apart_from_the_app_overhead() {
    List<TxnRecord> txns = List.of(ok(Flow.P2P, "t1", 0, 40), ok(Flow.USSD_P2P, "u1", 0, 900));

    Overhead.Result r = Overhead.compute(txns, Map.of(), Map.of(), WINDOW);

    assertThat(r.overall().count()).isEqualTo(1);
    assertThat(r.overall().max()).isEqualTo(40);
    assertThat(r.ussdSessions().max()).isEqualTo(900);
  }
}
