package mfs.mw.app.gateway.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.dto.TransactionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code app_transaction} against PostgreSQL (Testcontainers, the module's JVM-scoped {@link
 * TestInfrastructure}): the newest status wins whatever the arrival order, the accepted row never
 * overwrites a status, rows are read only by their initiator, and a repeated key's minted txnId
 * resolves to the stored transaction (S3).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.app-gateway.consumers.enabled=false", "mw.outbox.relay.enabled=false"})
@Import(TestInfrastructure.class)
class AppTransactionRepositoryIT {

  private static final Instant T0 = Instant.parse("2026-11-03T08:15:30Z");
  private static final String PAYER = "01711000001";

  private final AppTransactionRepository repo;
  private final JdbcTemplate jdbc;

  @Autowired
  AppTransactionRepositoryIT(AppTransactionRepository repo, JdbcTemplate jdbc) {
    this.repo = repo;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from app_transaction");
  }

  private static TransactionView view(UUID txnId, String status) {
    return new TransactionView(txnId.toString(), status, "P2P", null, null, null, null, null, null, T0, T0);
  }

  @Test
  void theNewestStatusWinsWhateverTheOrder() {
    UUID txn = UUID.randomUUID();
    repo.upsert(PAYER, "CUSTOMER_APP", view(txn, "POSTING"), T0.plusSeconds(2));
    repo.upsert(PAYER, "CUSTOMER_APP", view(txn, "VALIDATING"), T0.plusSeconds(1));
    assertThat(repo.find(txn, PAYER)).map(TransactionView::status).contains("POSTING");

    repo.upsert(PAYER, "CUSTOMER_APP", view(txn, "COMPLETED"), T0.plusSeconds(3));
    assertThat(repo.find(txn, PAYER)).map(TransactionView::status).contains("COMPLETED");
  }

  @Test
  void theAcceptedRowNeverOverwritesAStatusThatGotThereFirst() {
    UUID txn = UUID.randomUUID();
    repo.upsert(PAYER, "CUSTOMER_APP", view(txn, "COMPLETED"), T0.plusSeconds(3));

    repo.insertAccepted(PAYER, "CUSTOMER_APP", view(txn, "RECEIVED"), T0);

    assertThat(repo.find(txn, PAYER)).map(TransactionView::status).contains("COMPLETED");
  }

  @Test
  void onlyTheInitiatorReadsARow() {
    UUID txn = UUID.randomUUID();
    repo.insertAccepted(PAYER, "CUSTOMER_APP", view(txn, "RECEIVED"), T0);

    assertThat(repo.find(txn, PAYER)).isPresent();
    assertThat(repo.find(txn, "01711000099")).isEmpty();
    assertThat(repo.find(UUID.randomUUID(), PAYER)).isEmpty();
  }

  @Test
  void aLinkedRepeatShowsTheStoredTransactionUpToItsFinalStatus() {
    UUID stored = UUID.randomUUID();
    UUID minted = UUID.randomUUID();
    repo.insertAccepted(PAYER, "CUSTOMER_APP", view(stored, "RECEIVED"), T0);
    repo.insertAccepted(PAYER, "CUSTOMER_APP", view(minted, "RECEIVED"), T0.plusSeconds(1));

    repo.linkRepeat(minted, stored, PAYER, "CUSTOMER_APP", view(stored, "POSTING"), T0.plusSeconds(2));
    repo.upsert(PAYER, "CUSTOMER_APP", view(stored, "COMPLETED"), T0.plusSeconds(3));

    assertThat(repo.find(minted, PAYER)).hasValueSatisfying(v -> {
      assertThat(v.txnId()).isEqualTo(stored.toString());
      assertThat(v.status()).isEqualTo("COMPLETED");
    });
    assertThat(repo.find(minted, "01711000099")).isEmpty();
  }

  @Test
  void aDuplicateAnswerThatOutrunsTheAcceptedRowStillLinksIt() {
    UUID stored = UUID.randomUUID();
    UUID minted = UUID.randomUUID();

    repo.linkRepeat(minted, stored, PAYER, "CUSTOMER_APP", view(stored, "POSTING"), T0.plusSeconds(2));
    repo.insertAccepted(PAYER, "CUSTOMER_APP", view(minted, "RECEIVED"), T0.plusSeconds(1));

    // the stored row is not here (yet): the answer's view of it is shown
    assertThat(repo.find(minted, PAYER)).hasValueSatisfying(v -> {
      assertThat(v.txnId()).isEqualTo(stored.toString());
      assertThat(v.status()).isEqualTo("POSTING");
    });
  }

  @Test
  void anotherInitiatorCannotLinkSomeoneElsesRow() {
    UUID minted = UUID.randomUUID();
    repo.insertAccepted(PAYER, "CUSTOMER_APP", view(minted, "RECEIVED"), T0);

    repo.linkRepeat(minted, UUID.randomUUID(), "01711000099", "CUSTOMER_APP", view(minted, "POSTING"), T0.plusSeconds(1));

    assertThat(repo.find(minted, PAYER)).map(TransactionView::txnId).contains(minted.toString());
  }
}
