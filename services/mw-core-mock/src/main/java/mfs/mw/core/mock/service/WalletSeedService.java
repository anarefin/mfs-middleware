package mfs.mw.core.mock.service;

import java.util.regex.Pattern;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.WalletBalance;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import mfs.mw.core.mock.repository.WalletRepository;
import mfs.mw.core.mock.util.MaskingPolicy;
import mfs.mw.core.mock.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-only wallet seeding (ticket 57, used by mw-seeder): create-if-absent with the seed PIN and an
 * opening balance (no ledger entries, as {@link WalletSeeder} seeds its own ranges). An existing
 * wallet is returned as it is and never changed.
 */
@Service
public class WalletSeedService {

  private static final Logger log = LoggerFactory.getLogger(WalletSeedService.class);
  private static final Pattern MSISDN = Pattern.compile("01[3-9]\\d{8}");

  /** The wallet now; {@code created} when this call made it. */
  public record Seeded(boolean created, WalletBalance wallet) {}

  private final WalletRepository wallets;
  private final WalletBalances balances;
  private final PasswordEncoder encoder;
  private final CoreMockProperties props;

  public WalletSeedService(
      WalletRepository wallets, WalletBalances balances, PasswordEncoder encoder, CoreMockProperties props) {
    this.wallets = wallets;
    this.balances = balances;
    this.encoder = encoder;
    this.props = props;
  }

  @Transactional
  public Seeded seed(String walletId, String type, long openingBalanceMinor) {
    if (walletId == null || !MSISDN.matcher(walletId).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "walletId must be a mobile number");
    }
    boolean created =
        wallets.resolveId(walletId).isEmpty()
            && wallets.insertIfAbsent(walletId, type, openingBalanceMinor, encoder.encode(props.seed().pin()));
    if (created) {
      log.atInfo()
          .addKeyValue("event", "coremock.wallet.seeded")
          .log("seeded {} wallet {}", type, MaskingPolicy.mask(SensitiveType.MSISDN, walletId));
    }
    WalletBalance now =
        balances.find(walletId).orElseThrow(() -> new IllegalStateException("seeded wallet not readable"));
    return new Seeded(created, now);
  }
}
