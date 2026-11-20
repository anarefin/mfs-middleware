package mfs.mw.adapter.atm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import mfs.mw.adapter.atm.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@code atm_inbound} against PostgreSQL (ticket 120b1, PLT-025): a row keeps its atmId,
 * fingerprint and state; a state moves once from its expected value, also under concurrency (a
 * confirm racing a reversal: exactly one wins).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class AtmInboundRepositoryIT {

  private static final Instant T0 = Instant.parse("2026-11-20T12:00:00Z");

  private final AtmInboundRepository repo;

  @Autowired
  AtmInboundRepositoryIT(AtmInboundRepository repo) {
    this.repo = repo;
  }

  @Test
  void aRowIsInsertedOnceAndReadBackWhole() {
    String ref = "ATM-" + UUID.randomUUID();
    AtmInboundStore.Row row = new AtmInboundStore.Row("txn-1", "in-1", "UCB-1", "ab".repeat(32), "VERIFIED");

    assertThat(repo.insert("ATM_VERIFY_DEBIT", ref, row, T0)).isTrue();
    assertThat(repo.insert("ATM_VERIFY_DEBIT", ref,
        new AtmInboundStore.Row("txn-2", "in-2", "UCB-2", "cd".repeat(32), "VERIFIED"), T0)).isFalse();
    assertThat(repo.find("ATM_VERIFY_DEBIT", ref)).contains(row);
  }

  @Test
  void aConfirmRacingAReversalLetsExactlyOneWin() {
    String ref = "ATM-" + UUID.randomUUID();
    repo.insert("ATM_VERIFY_DEBIT", ref,
        new AtmInboundStore.Row("txn-1", "in-1", "UCB-1", "ab".repeat(32), "VERIFIED"), T0);

    List<Boolean> won = IntStream.range(0, 8)
        .mapToObj(i -> CompletableFuture.supplyAsync(() ->
            repo.transition("ATM_VERIFY_DEBIT", ref, "VERIFIED", i % 2 == 0 ? "CONFIRMED" : "REVERSED")))
        .toList().stream().map(CompletableFuture::join).toList();

    assertThat(won).containsOnlyOnce(true);
    assertThat(repo.find("ATM_VERIFY_DEBIT", ref).orElseThrow().state()).isIn("CONFIRMED", "REVERSED");
  }
}
