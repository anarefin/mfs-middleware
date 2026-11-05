package mfs.mw.core.mock.service;

import mfs.mw.core.mock.config.CoreMockProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the ledger: 1,000 customers ({@code 01711000001..}), 50 agents ({@code 01811000001..}),
 * 20 merchants ({@code 01911000001..}), 2 orgs ({@code 01311000001..}) and two system wallets
 * ({@link #FEE_WALLET} collects fees, {@link #SETTLEMENT_WALLET} is the outside world). Ids, types and balances are fixed, so the seed is
 * identical on every run; all wallets share one fake PIN (its bcrypt hash is computed once).
 */
@Service
public class WalletSeeder {

  /** Collects the fees; part of the balance invariant like any other wallet. */
  public static final String FEE_WALLET = "FEE-POOL";

  /** The outside world: partners' settlement and inbound money. Funded so credits can draw on it. */
  public static final String SETTLEMENT_WALLET = "SETTLEMENT";

  public static final long SETTLEMENT_SEED_MINOR = 1_000_000_000_000L;

  private static final Logger log = LoggerFactory.getLogger(WalletSeeder.class);

  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final CoreMockProperties props;

  public WalletSeeder(JdbcTemplate jdbc, PasswordEncoder encoder, CoreMockProperties props) {
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.props = props;
  }

  /** Adds the seed wallets that are missing; existing rows are left alone. */
  @Transactional
  public void seed() {
    String pinHash = encoder.encode(props.seed().pin());
    CoreMockProperties.Seed s = props.seed();
    insert("CUSTOMER", "0171100", 1000, s.customerBalanceMinor(), pinHash);
    insert("AGENT", "0181100", 50, s.agentBalanceMinor(), pinHash);
    insert("MERCHANT", "0191100", 20, s.merchantBalanceMinor(), pinHash);
    insert("ORG", "0131100", 2, s.orgBalanceMinor(), pinHash);
    jdbc.update(
        "insert into wallet (id, msisdn, type, balance_minor, pin_hash) "
            + "values (?, null, 'SYSTEM', 0, ?) on conflict do nothing",
        FEE_WALLET,
        pinHash);
    jdbc.update(
        "insert into wallet (id, msisdn, type, balance_minor, pin_hash) "
            + "values (?, null, 'SYSTEM', ?, ?) on conflict do nothing",
        SETTLEMENT_WALLET,
        SETTLEMENT_SEED_MINOR,
        pinHash);
  }

  /** Wipes the ledger (wallets, entries, holds, command log) and seeds it again. */
  @Transactional
  public void reset() {
    jdbc.execute("truncate entry, hold, command_log, reversal, wallet");
    seed();
    log.atInfo().addKeyValue("event", "coremock.seed.reset").log("ledger reset to the seed");
  }

  private void insert(String type, String prefix, int count, long balance, String pinHash) {
    jdbc.update(
        "insert into wallet (id, msisdn, type, balance_minor, pin_hash) "
            + "select ? || lpad(n::text, 4, '0'), ? || lpad(n::text, 4, '0'), ?, ?, ? "
            + "from generate_series(1, ?) n on conflict do nothing",
        prefix,
        prefix,
        type,
        balance,
        pinHash,
        count);
  }
}
