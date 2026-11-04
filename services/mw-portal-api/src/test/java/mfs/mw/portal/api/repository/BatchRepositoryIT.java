package mfs.mw.portal.api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.portal.api.TestInfrastructure;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code bulk.batch} / {@code batch_item} against PostgreSQL: decisions are conditional, maker-checker holds in the schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "mw.outbox.relay.enabled=false")
@Import(TestInfrastructure.class)
class BatchRepositoryIT {

  private static final Instant T0 = Instant.parse("2026-11-04T08:00:00Z");

  private final BatchRepository repo;
  private final JdbcTemplate jdbc;

  @Autowired
  BatchRepositoryIT(BatchRepository repo, JdbcTemplate jdbc) {
    this.repo = repo;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from batch_item");
    jdbc.update("delete from batch");
  }

  static Batch pending(UUID id) {
    return new Batch(
        id, "ORG001", BatchStatus.PENDING_APPROVAL, 1, 100L, "BDT", "ab", "maker", "maker-name", null, null,
        null, T0, null);
  }

  @Test
  void aDecisionIsRecordedOnceAndNeverByTheMaker() {
    UUID id = UUID.randomUUID();
    repo.insert(pending(id), List.of(new BulkRow(2, "01711000001", 100L, "R1")));

    assertThat(repo.decide(id, "ORG001", BatchStatus.APPROVED, "maker", "maker-name", null, T0)).isFalse();
    assertThat(repo.decide(id, "ORG002", BatchStatus.APPROVED, "chk", "chk-name", null, T0)).isFalse();
    assertThat(repo.decide(id, "ORG001", BatchStatus.APPROVED, "chk", "chk-name", null, T0)).isTrue();
    assertThat(repo.decide(id, "ORG001", BatchStatus.REJECTED, "chk2", "chk2-name", "x", T0)).isFalse();

    assertThat(repo.find(id, "ORG001")).hasValueSatisfying(b -> {
      assertThat(b.status()).isEqualTo(BatchStatus.APPROVED);
      assertThat(b.checkerName()).isEqualTo("chk-name");
      assertThat(b.decidedAt()).isEqualTo(T0);
    });
    assertThat(repo.find(id, "ORG002")).isEmpty();
  }

  @Test
  void theSchemaRefusesTheMakerAsChecker() {
    UUID id = UUID.randomUUID();
    repo.insert(pending(id), List.of(new BulkRow(2, "01711000001", 100L, "R1")));
    assertThatThrownBy(() -> jdbc.update("update batch set checker_sub = maker_sub where batch_id = ?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
