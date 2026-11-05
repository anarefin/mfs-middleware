package mfs.mw.audit.dw.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The scheduled check of every month's chain (ticket 50): result, alert metric. */
class ChainVerifyServiceTest {

  /** A verifier answering from a fixed table instead of the database. */
  static class FixedVerifier extends AuditChainVerifier {
    private final Map<String, ChainVerification> results;

    FixedVerifier(Map<String, ChainVerification> results) {
      super(null);
      this.results = results;
    }

    @Override
    public List<String> months() {
      return results.keySet().stream().sorted().toList();
    }

    @Override
    public ChainVerification verify(String month) {
      return results.get(month);
    }
  }

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private double brokenGauge() {
    return meters.get("mw.audit.chain.broken").gauge().value();
  }

  @Test
  void everyMonthIsVerifiedAndAnIntactTrailReportsNoBrokenChain() {
    var service =
        new ChainVerifyService(
            new FixedVerifier(
                Map.of("2026-10", ChainVerification.ok("2026-10", 4), "2026-11", ChainVerification.ok("2026-11", 2))),
            meters);

    List<ChainVerification> results = service.verifyAll();

    assertThat(results).extracting(ChainVerification::month).containsExactly("2026-10", "2026-11");
    assertThat(brokenGauge()).isZero();
  }

  @Test
  void aBrokenChainIsReportedAndCountedInTheMetric() {
    var service =
        new ChainVerifyService(
            new FixedVerifier(
                Map.of(
                    "2026-10", ChainVerification.ok("2026-10", 4),
                    "2026-11", ChainVerification.broken("2026-11", 5, 3, "hash mismatch"))),
            meters);

    List<ChainVerification> results = service.verifyAll();

    assertThat(results).filteredOn(v -> !v.intact()).extracting(ChainVerification::failedSeq).containsExactly(3L);
    assertThat(brokenGauge()).isEqualTo(1.0);
  }

  @Test
  void theMetricClearsOnceTheNextRunFindsTheChainsIntact() {
    var broken = new java.util.HashMap<String, ChainVerification>();
    broken.put("2026-11", ChainVerification.broken("2026-11", 5, 3, "hash mismatch"));
    var service = new ChainVerifyService(new FixedVerifier(broken), meters);
    service.verifyAll();
    broken.put("2026-11", ChainVerification.ok("2026-11", 5));

    service.verifyAll();

    assertThat(brokenGauge()).isZero();
  }

  @Test
  void aMonthThatCannotBeReadCountsAsBrokenAndTheOthersAreStillChecked() {
    AuditChainVerifier failing =
        new FixedVerifier(Map.of("2026-10", ChainVerification.ok("2026-10", 1), "2026-11", ChainVerification.ok("2026-11", 1))) {
          @Override
          public ChainVerification verify(String month) {
            if (month.equals("2026-10")) {
              throw new IllegalStateException("permission denied");
            }
            return super.verify(month);
          }
        };
    var service = new ChainVerifyService(failing, meters);

    List<ChainVerification> results = service.verifyAll();

    assertThat(results).extracting(ChainVerification::month).containsExactly("2026-10", "2026-11");
    assertThat(results.get(0).intact()).isFalse();
    assertThat(brokenGauge()).isEqualTo(1.0);
  }
}
