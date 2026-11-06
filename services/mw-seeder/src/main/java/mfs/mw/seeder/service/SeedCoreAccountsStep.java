package mfs.mw.seeder.service;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.seeder.client.CoreAccounts;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.WalletSpec;
import mfs.mw.seeder.dto.SeedManifest;
import mfs.mw.seeder.util.MaskingPolicy;
import mfs.mw.seeder.util.SensitiveType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * seed-core-accounts: the wallets of {@code mw.seeder.wallets} (customers, agents, merchants, orgs)
 * in mw-core-mock, through its test-only seed API (create-if-absent; an existing wallet keeps its
 * balance, which tests may have moved). Billers need nothing seeded: a BILLER_ACCOUNT is outside the
 * core (no wallet type), the biller catalogue is static mw-adapter-billpay config with no write API,
 * and the simulated aggregator derives bills from their number (CLAUDE.md, seed-core-accounts).
 */
@Service
@Order(3)
public class SeedCoreAccountsStep implements SeedStep {

  private final CoreAccounts core;
  private final SeederProperties props;

  public SeedCoreAccountsStep(CoreAccounts core, SeederProperties props) {
    this.core = core;
    this.props = props;
  }

  @Override
  public String id() {
    return "seed-core-accounts";
  }

  @Override
  public StepResult run(SeedContext ctx) {
    StepResult result = StepResult.unchanged(0);
    List<SeedManifest.Wallet> wallets = new ArrayList<>();
    for (WalletSpec w : props.wallets()) {
      CoreAccounts.Seeded seeded = core.ensureWallet(w.walletId(), w.type(), w.openingBalanceMinor());
      if (!w.type().equals(seeded.wallet().type())) {
        throw new IllegalStateException(
            "wallet " + MaskingPolicy.mask(SensitiveType.MSISDN, w.walletId()) + " exists as "
                + seeded.wallet().type() + ", not " + w.type());
      }
      result = result.plus(seeded.created() ? StepResult.created(1) : StepResult.unchanged(1));
      wallets.add(new SeedManifest.Wallet(w.walletId(), w.type(), w.openingBalanceMinor()));
    }
    ctx.wallets().addAll(wallets);
    return result;
  }
}
