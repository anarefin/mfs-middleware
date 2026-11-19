package mfs.mw.core.mock.service;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.repository.SheetRepository;
import mfs.mw.core.mock.util.HolderNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the ledger from the versioned files {@code seed/<version>/} ({@link SeedFiles}, ticket 94, FRS
 * 7.3): 1,000 customers ({@code 01711000001..}, the last 100 BASIC KYC), 50 agents ({@code
 * 01811000001..}), 10 distributors ({@code 01611000001..}), 20 merchants ({@code 01911000001..}), 3
 * organisations ({@code 01311000001..}), the system wallets ({@link #FEE_INCOME}, {@link #VAT_PAYABLE},
 * {@link #COMMISSION_PAYABLE}, {@link #SETTLEMENT_WALLET} and one settlement wallet per partner) and
 * the tariff and limit sheets. Ids, types and balances are fixed, so the seed is identical on every
 * run; wallets already there are left alone (a missing holder name is filled in), the sheets are
 * replaced by the files. Each generated wallet gets a fixed display name ({@link HolderNames}). No
 * PIN: the core holds none since ticket 72 (mw-auth owns the PINs).
 */
@Service
public class WalletSeeder {

  /** The fee net of VAT and commission (FRS 7.1 fee income; FEE-POOL before ticket 94). */
  public static final String FEE_INCOME = "FEE_INCOME";

  /** The VAT inside the fees. */
  public static final String VAT_PAYABLE = "VAT_PAYABLE";

  /** The agents' and distributors' commission shares of the fees. */
  public static final String COMMISSION_PAYABLE = "COMMISSION_PAYABLE";

  /** The outside world when no partner is named: settlement and inbound money. Funded so credits can draw on it. */
  public static final String SETTLEMENT_WALLET = "SETTLEMENT";

  /** Every partner settlement wallet's id starts with this ({@code SETTLEMENT_OCS_GP}, ...). */
  public static final String SETTLEMENT_PREFIX = "SETTLEMENT";

  public static final long SETTLEMENT_SEED_MINOR = 1_000_000_000_000L;

  private static final Logger log = LoggerFactory.getLogger(WalletSeeder.class);

  private final JdbcTemplate jdbc;
  private final SheetRepository sheets;
  private final WalletStateEvents stateEvents;
  private final SeedFiles files;

  public WalletSeeder(JdbcTemplate jdbc, SheetRepository sheets, CoreMockProperties props, WalletStateEvents stateEvents) {
    this.jdbc = jdbc;
    this.sheets = sheets;
    this.stateEvents = stateEvents;
    this.files = new SeedFiles(props.seed().version());
  }

  /**
   * Adds the seed wallets that are missing; existing rows are left alone, except that a missing
   * holder name is filled in. Each wallet added is published as WalletStateChanged CREATED in the
   * same transaction (ticket 67). The tariff and limit sheets become the files' rows.
   */
  @Transactional
  public void seed() {
    List<SeedFiles.WalletRange> ranges = files.wallets();
    List<String> created = new ArrayList<>();
    for (SeedFiles.WalletRange r : ranges) {
      created.addAll(insert(r));
    }
    stateEvents.created(created);
    ranges.forEach(this::nameHolders);
    for (SeedFiles.SystemWallet w : files.systemWallets()) {
      jdbc.update(
          "insert into wallet (id, msisdn, type, balance_minor) values (?, null, 'SYSTEM', ?) on conflict do nothing",
          w.id(), w.openingBalanceMinor());
    }
    sheets.replaceTariff(files.tariff());
    sheets.replaceLimits(files.limits());
    log.atInfo().addKeyValue("event", "coremock.seed.done").addKeyValue("created", created.size())
        .log("ledger seeded from seed files");
  }

  /** Wipes the ledger (wallets, entries, holds, command log, REST request hashes) and seeds it again. */
  @Transactional
  public void reset() {
    jdbc.execute("truncate entry, hold, command_log, rest_request, reversal, wallet");
    seed();
    log.atInfo().addKeyValue("event", "coremock.seed.reset").log("ledger reset to the seed");
  }

  /** Gives every wallet of a range that has no holder name yet its fixed one. */
  private void nameHolders(SeedFiles.WalletRange r) {
    List<Object[]> rows = new ArrayList<>();
    for (int n = r.first(); n <= r.last(); n++) {
      rows.add(new Object[] {HolderNames.of(r.type(), n), r.id(n)});
    }
    jdbc.batchUpdate("update wallet set holder_name = ? where id = ? and holder_name is null", rows);
  }

  /** Inserts the missing wallets of a range; returns the ids it inserted. */
  private List<String> insert(SeedFiles.WalletRange r) {
    return jdbc.queryForList(
        "insert into wallet (id, msisdn, type, kyc_level, balance_minor) "
            + "select ? || lpad(n::text, 4, '0'), ? || lpad(n::text, 4, '0'), ?, ?, ? "
            + "from generate_series(?, ?) n on conflict do nothing returning id",
        String.class,
        r.prefix(),
        r.prefix(),
        r.type(),
        r.kycLevel(),
        r.openingBalanceMinor(),
        r.first(),
        r.last());
  }
}
