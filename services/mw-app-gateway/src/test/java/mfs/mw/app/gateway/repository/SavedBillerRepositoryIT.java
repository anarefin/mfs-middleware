package mfs.mw.app.gateway.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.dto.SavedBiller;
import mfs.mw.app.gateway.util.MaskingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code saved_biller} against PostgreSQL (ticket 107a): the account number is stored in the clear,
 * and a wallet only reads and deletes its own rows.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.app-gateway.consumers.enabled=false", "mw.outbox.relay.enabled=false"})
@Import(TestInfrastructure.class)
class SavedBillerRepositoryIT {

  private static final String WALLET = "01711000001";
  private static final String OTHER = "01711000002";
  private static final String ACCOUNT = "9876543210";

  private final SavedBillerRepository repo;
  private final JdbcTemplate jdbc;

  @Autowired
  SavedBillerRepositoryIT(SavedBillerRepository repo, JdbcTemplate jdbc) {
    this.repo = repo;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from saved_biller");
  }

  @Test
  void theAccountNumberIsStoredInTheClearAndOnlyThatWalletSeesIt() {
    SavedBiller saved = repo.insert(WALLET, "DESCO", ACCOUNT, "home");

    String stored = jdbc.queryForObject(
        "select account_no from saved_biller where id = ?::uuid", String.class, saved.id());
    assertThat(stored).isEqualTo(ACCOUNT);
    assertThat(MaskingPolicy.describe(saved)).doesNotContain(ACCOUNT).contains("****3210");

    assertThat(repo.list(WALLET)).extracting(SavedBiller::accountNo).containsExactly(ACCOUNT);
    assertThat(repo.list(OTHER)).isEmpty();

    assertThat(repo.delete(OTHER, UUID.fromString(saved.id()))).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from saved_biller", Integer.class)).isEqualTo(1);
    assertThat(repo.delete(WALLET, UUID.fromString(saved.id()))).isTrue();
    assertThat(repo.list(WALLET)).isEmpty();
  }
}
