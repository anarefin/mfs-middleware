package mfs.mw.seeder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.seeder.client.CoreAccounts;
import mfs.mw.seeder.dto.SeedManifest;
import mfs.mw.seeder.dto.WalletView;
import org.junit.jupiter.api.Test;

class SeedCoreAccountsStepTest {

  /** In-memory core-mock seed API: create-if-absent, an existing wallet is never touched. */
  static final class FakeCore implements CoreAccounts {
    final Map<String, WalletView> wallets = new LinkedHashMap<>();
    final List<String> creates = new ArrayList<>();

    @Override
    public Seeded ensureWallet(String walletId, String type, long openingBalanceMinor) {
      WalletView have = wallets.get(walletId);
      if (have != null) {
        return new Seeded(false, have);
      }
      WalletView w = new WalletView(walletId, type, "ACTIVE", openingBalanceMinor, 0, openingBalanceMinor);
      wallets.put(walletId, w);
      creates.add(walletId);
      return new Seeded(true, w);
    }
  }

  private final FakeCore core = new FakeCore();
  private final SeedCoreAccountsStep step = new SeedCoreAccountsStep(core, SeedFixtures.props(Path.of("unused")));

  @Test
  void createsTheMissingWalletsWithTheirOpeningBalances() {
    SeedContext ctx = new SeedContext();

    assertThat(step.run(ctx)).isEqualTo(StepResult.created(2));
    assertThat(core.wallets.get("01812000001").balanceMinor()).isEqualTo(5_000_000);
    assertThat(ctx.wallets())
        .containsExactly(
            new SeedManifest.Wallet("01712000001", "CUSTOMER", 1_000_000),
            new SeedManifest.Wallet("01812000001", "AGENT", 5_000_000));
  }

  @Test
  void aWalletThatAlreadyExistsIsLeftAsItIsEvenWhenItsBalanceMoved() {
    core.wallets.put("01712000001", new WalletView("01712000001", "CUSTOMER", "ACTIVE", 42, 0, 42));
    step.run(new SeedContext());
    core.creates.clear();

    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.unchanged(2));
    assertThat(core.creates).isEmpty();
    assertThat(core.wallets.get("01712000001").balanceMinor()).isEqualTo(42);
  }

  @Test
  void anExistingWalletOfAnotherTypeFailsTheStep() {
    core.wallets.put("01812000001", new WalletView("01812000001", "MERCHANT", "ACTIVE", 0, 0, 0));

    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MERCHANT")
        .hasMessageNotContaining("01812000001");
  }
}
