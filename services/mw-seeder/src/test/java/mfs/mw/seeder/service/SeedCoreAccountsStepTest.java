package mfs.mw.seeder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.seeder.client.CoreAccounts;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.WalletSpec;
import mfs.mw.seeder.dto.SeedManifest;
import mfs.mw.seeder.dto.WalletView;
import org.junit.jupiter.api.Test;

class SeedCoreAccountsStepTest {

  /** In-memory core-mock seed API: create-if-absent, an existing wallet only gets a holder name it lacks. */
  static class FakeCore implements CoreAccounts {
    final Map<String, WalletView> wallets = new LinkedHashMap<>();
    final List<String> creates = new ArrayList<>();

    @Override
    public Seeded ensureWallet(String walletId, String type, long openingBalanceMinor, String holderName) {
      WalletView have = wallets.get(walletId);
      if (have != null) {
        if (holderName != null && have.holderName() == null) {
          WalletView named = new WalletView(walletId, have.type(), have.status(), have.balanceMinor(), have.heldMinor(),
              have.availableMinor(), holderName);
          wallets.put(walletId, named);
          creates.add("name " + walletId);
          return new Seeded(false, true, named);
        }
        return new Seeded(false, false, have);
      }
      WalletView w = new WalletView(walletId, storedType(type), "ACTIVE", openingBalanceMinor, 0, openingBalanceMinor, holderName);
      wallets.put(walletId, w);
      creates.add(walletId);
      return new Seeded(true, false, w);
    }

    /** Core-mock's seed PUT stores ORG as ORGANISATION (ticket 94). */
    String storedType(String type) {
      return type;
    }
  }

  /** The real core-mock: a requested ORG comes back as ORGANISATION. */
  static final class CanonicalCore extends FakeCore {
    @Override
    String storedType(String type) {
      return "ORG".equals(type) ? "ORGANISATION" : type;
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
            new SeedManifest.Wallet("01712000001", "CUSTOMER", "Sumaiya Islam", 1_000_000),
            new SeedManifest.Wallet("01812000001", "AGENT", null, 5_000_000));
    assertThat(core.wallets.get("01712000001").holderName()).isEqualTo("Sumaiya Islam");
  }

  @Test
  void anExistingWalletWithoutAHolderNameGetsItOnceAndNothingElse() {
    core.wallets.put("01712000001", new WalletView("01712000001", "CUSTOMER", "ACTIVE", 42, 0, 42, null));

    assertThat(step.run(new SeedContext())).isEqualTo(new StepResult(1, 1, 0));
    assertThat(core.wallets.get("01712000001").holderName()).isEqualTo("Sumaiya Islam");
    assertThat(core.wallets.get("01712000001").balanceMinor()).isEqualTo(42);
    core.creates.clear();

    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.unchanged(2));
    assertThat(core.creates).isEmpty();
  }

  @Test
  void aWalletThatAlreadyExistsIsLeftAsItIsEvenWhenItsBalanceMoved() {
    core.wallets.put("01712000001", new WalletView("01712000001", "CUSTOMER", "ACTIVE", 42, 0, 42, "Sumaiya Islam"));
    step.run(new SeedContext());
    core.creates.clear();

    assertThat(step.run(new SeedContext())).isEqualTo(StepResult.unchanged(2));
    assertThat(core.creates).isEmpty();
    assertThat(core.wallets.get("01712000001").balanceMinor()).isEqualTo(42);
  }

  @Test
  void anOrgRequestMatchesTheOrganisationCoreMockStores() {
    SeederProperties base = SeedFixtures.props(Path.of("unused"));
    SeederProperties props = new SeederProperties(
        base.runOnStartup(), base.stepTimeout(), base.stepTimeouts(), base.initialBackoff(), base.maxBackoff(),
        base.manifestPath(), base.http(), base.endpoints(), base.readiness(), base.keycloakAdmin(), base.realms(),
        base.users(), base.importedUsers(), base.operators(),
        List.of(new WalletSpec("01312000001", "ORG", 100_000_000, "Jamuna Textiles Ltd")),
        base.partners(), base.channelClients(), base.content(), base.appPins());
    CanonicalCore orgCore = new CanonicalCore();
    SeedCoreAccountsStep orgStep = new SeedCoreAccountsStep(orgCore, props);
    SeedContext ctx = new SeedContext();

    assertThat(orgStep.run(ctx)).isEqualTo(StepResult.created(1));
    assertThat(orgCore.wallets.get("01312000001").type()).isEqualTo("ORGANISATION");
    assertThat(ctx.wallets())
        .containsExactly(new SeedManifest.Wallet("01312000001", "ORG", "Jamuna Textiles Ltd", 100_000_000));

    assertThat(orgStep.run(new SeedContext())).isEqualTo(StepResult.unchanged(1));
  }

  @Test
  void anExistingWalletOfAnotherTypeFailsTheStep() {
    core.wallets.put("01812000001", new WalletView("01812000001", "MERCHANT", "ACTIVE", 0, 0, 0, null));

    assertThatThrownBy(() -> step.run(new SeedContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MERCHANT")
        .hasMessageNotContaining("01812000001");
  }
}
