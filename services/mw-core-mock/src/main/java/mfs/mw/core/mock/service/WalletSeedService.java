package mfs.mw.core.mock.service;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.repository.LedgerRepository;
import mfs.mw.core.mock.dto.WalletBalance;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import mfs.mw.core.mock.repository.WalletRepository;
import mfs.mw.core.mock.util.MaskingPolicy;
import mfs.mw.core.mock.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only wallet seeding (ticket 57, used by mw-seeder): create-if-absent with an
 * opening balance (no ledger entries, as {@link WalletSeeder} seeds its own ranges). An existing
 * wallet is returned as it is and never changed, except that a holder name it lacks is filled in and,
 * when {@code balanceMinor} is given, its available balance is set to it: the difference is posted as
 * an ADJUSTMENT double entry between the wallet and {@link WalletSeeder#SETTLEMENT_WALLET}, so the
 * ledger stays balanced and the change is visible in the entries.
 */
@Service
public class WalletSeedService {

  private static final Logger log = LoggerFactory.getLogger(WalletSeedService.class);
  private static final Pattern MSISDN = Pattern.compile("01[3-9]\\d{8}");

  /** The wallet now; {@code created} when this call made it. */
  public record Seeded(boolean created, WalletBalance wallet) {}

  private final WalletRepository wallets;
  private final WalletBalances balances;
  private final WalletStateEvents stateEvents;
  private final LedgerRepository ledger;
  private final Clock clock;

  public WalletSeedService(
      WalletRepository wallets,
      WalletBalances balances,
      WalletStateEvents stateEvents,
      LedgerRepository ledger,
      Clock clock) {
    this.wallets = wallets;
    this.balances = balances;
    this.stateEvents = stateEvents;
    this.ledger = ledger;
    this.clock = clock;
  }

  @Transactional
  public Seeded seed(String walletId, String type, long openingBalanceMinor, String holderName, Long balanceMinor) {
    if (walletId == null || !MSISDN.matcher(walletId).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "walletId must be a mobile number");
    }
    boolean exists = wallets.resolveId(walletId).isPresent();
    boolean created = !exists && wallets.insertIfAbsent(walletId, type, openingBalanceMinor, holderName);
    if (exists && holderName != null) {
      wallets.fillHolderName(walletId, holderName);
    }
    if (created) {
      stateEvents.created(java.util.List.of(walletId));
      log.atInfo()
          .addKeyValue("event", "coremock.wallet.seeded")
          .log("seeded {} wallet {}", type, MaskingPolicy.mask(SensitiveType.MSISDN, walletId));
    }
    if (balanceMinor != null) {
      setAvailable(walletId, balanceMinor);
    }
    WalletBalance now =
        balances.find(walletId).orElseThrow(() -> new IllegalStateException("seeded wallet not readable"));
    return new Seeded(created, now);
  }

  /** Posts the difference to {@code availableMinor} against SETTLEMENT (both rows locked, wallet first). */
  private void setAvailable(String walletId, long availableMinor) {
    Wallet w = wallets.lock(walletId);
    long delta = availableMinor + w.heldMinor() - w.balanceMinor();
    if (delta == 0) {
      return;
    }
    wallets.lock(WalletSeeder.SETTLEMENT_WALLET);
    String ref = "adjust:" + UUID.randomUUID();
    long amount = Math.abs(delta);
    String from = delta > 0 ? WalletSeeder.SETTLEMENT_WALLET : walletId;
    String to = delta > 0 ? walletId : WalletSeeder.SETTLEMENT_WALLET;
    var at = clock.instant();
    ledger.append(ref, ref, from, "DEBIT", "ADJUSTMENT", amount, at);
    ledger.append(ref, ref, to, "CREDIT", "ADJUSTMENT", amount, at);
    wallets.adjustBalance(from, -amount);
    wallets.adjustBalance(to, amount);
    log.atInfo()
        .addKeyValue("event", "coremock.wallet.balance.set")
        .log("set available balance of {} to {} ({} {})", MaskingPolicy.mask(SensitiveType.MSISDN, walletId),
            availableMinor, delta > 0 ? "credit" : "debit", amount);
  }
}
