package mfs.mw.core.mock.service;

import java.util.Optional;
import mfs.mw.core.mock.dto.WalletBalance;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.repository.WalletRepository;
import org.springframework.stereotype.Service;

/** Read-only view of wallet balances for the test/admin API. */
@Service
public class WalletBalances {

  private final WalletRepository wallets;

  public WalletBalances(WalletRepository wallets) {
    this.wallets = wallets;
  }

  /** The balances of the wallet whose id or MSISDN is {@code idOrMsisdn}, if it exists. */
  public Optional<WalletBalance> find(String idOrMsisdn) {
    return wallets.resolveId(idOrMsisdn).map(wallets::get).map(WalletBalances::view);
  }

  private static WalletBalance view(Wallet w) {
    return new WalletBalance(w.id(), w.type(), w.status(), w.balanceMinor(), w.heldMinor(), w.availableMinor());
  }
}
