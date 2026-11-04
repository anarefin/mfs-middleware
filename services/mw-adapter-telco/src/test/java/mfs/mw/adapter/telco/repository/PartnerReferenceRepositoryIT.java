package mfs.mw.adapter.telco.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import mfs.mw.adapter.telco.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code partner_reference} against PostgreSQL (Testcontainers, the module's JVM-scoped {@link
 * TestInfrastructure}): one stable reference per txnId (spec 7.3 rule 5), whoever asks first,
 * also under concurrency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class PartnerReferenceRepositoryIT {

  private static final Instant T0 = Instant.parse("2026-11-03T08:15:30Z");

  private final PartnerReferenceRepository repo;
  private final JdbcTemplate jdbc;

  @Autowired
  PartnerReferenceRepositoryIT(PartnerReferenceRepository repo, JdbcTemplate jdbc) {
    this.repo = repo;
    this.jdbc = jdbc;
  }

  @Test
  void theFirstReferenceOfATxnIsKeptAndReturnedToEveryLaterCaller() {
    String txnId = UUID.randomUUID().toString();

    assertThat(repo.findOrCreate(txnId, "gp", "REF-FIRST", T0)).isEqualTo("REF-FIRST");
    assertThat(repo.findOrCreate(txnId, "gp", "REF-SECOND", T0.plusSeconds(5))).isEqualTo("REF-FIRST");
    assertThat(jdbc.queryForObject("select count(*) from partner_reference where txn_id = ?", Integer.class, txnId))
        .isEqualTo(1);
  }

  @Test
  void concurrentCallersForOneTxnAllGetTheSameReference() {
    String txnId = UUID.randomUUID().toString();

    List<String> refs =
        IntStream.range(0, 8)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> repo.findOrCreate(txnId, "gp", "REF-" + i, T0)))
            .toList()
            .stream()
            .map(CompletableFuture::join)
            .toList();

    assertThat(refs).hasSize(8).containsOnly(refs.get(0));
  }
}
