package mfs.mw.app.gateway.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.dto.Favourite;
import mfs.mw.app.gateway.util.MaskingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code favourite} against PostgreSQL (ticket 107a): the counterparty MSISDN is stored in the
 * clear, and a wallet only reads and deletes its own rows.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.app-gateway.consumers.enabled=false", "mw.outbox.relay.enabled=false"})
@Import(TestInfrastructure.class)
class FavouriteRepositoryIT {

  private static final String WALLET = "01711000001";
  private static final String OTHER = "01711000002";
  private static final String MSISDN = "01711000099";

  private final FavouriteRepository repo;
  private final JdbcTemplate jdbc;

  @Autowired
  FavouriteRepositoryIT(FavouriteRepository repo, JdbcTemplate jdbc) {
    this.repo = repo;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from favourite");
  }

  @Test
  void theCounterpartyIsStoredInTheClearAndOnlyThatWalletSeesIt() {
    Favourite saved = repo.insert(WALLET, MSISDN, "Amma");

    String stored = jdbc.queryForObject(
        "select counterparty_msisdn from favourite where id = ?::uuid", String.class, saved.id());
    assertThat(stored).isEqualTo(MSISDN);
    assertThat(MaskingPolicy.describe(saved)).doesNotContain(MSISDN).contains("0171****099");

    assertThat(repo.list(WALLET)).extracting(Favourite::msisdn).containsExactly(MSISDN);
    assertThat(repo.list(OTHER)).isEmpty();

    assertThat(repo.delete(OTHER, UUID.fromString(saved.id()))).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from favourite", Integer.class)).isEqualTo(1);
    assertThat(repo.delete(WALLET, UUID.fromString(saved.id()))).isTrue();
    assertThat(repo.list(WALLET)).isEmpty();
  }
}
