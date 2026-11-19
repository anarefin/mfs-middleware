package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.Party;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ledger invariants of FRS 7.3 after a mixed run (ticket 94): P2P, cash out with agent and
 * distributor commission, a top-up hold committed to a partner settlement wallet, a released hold, a
 * credit from a partner settlement wallet and a reversal. Every txnId's entries sum to zero, the
 * system wallets hold exactly the fee income, VAT and commission of the seeded tariff (5 Tk P2P, 10 Tk
 * cash out with 40 % / 10 % shares, 2 Tk top-up; VAT 15 % inside the fee), the sum of all balances is
 * unchanged and every entry carries the wallet's balance after it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class LedgerInvariantIT {

  static final String A = "01711000001";
  static final String B = "01711000002";
  static final String AGENT = "01811000001";

  private final CoreCommandService service;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final CoreCommandsTestSupport cmds;

  @Autowired
  LedgerInvariantIT(CoreCommandService service, WalletSeeder seeder, JdbcTemplate jdbc, JsonMapper json) {
    this.service = service;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
    this.cmds = new CoreCommandsTestSupport(json);
  }

  @BeforeEach
  void freshLedger() {
    seeder.reset();
  }

  private CoreEvent handle(CoreCommand c) {
    CoreEvent e = cmds.valid(service.handle(c).orElseThrow(), c);
    assertThat(e.outcome()).as("%s %s", c.commandType(), e.reasonCode()).isEqualTo(CoreEvent.Outcome.SUCCESS);
    return e;
  }

  private long balance(String id) {
    return jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, id);
  }

  private long total() {
    return jdbc.queryForObject("select sum(balance_minor) from wallet", Long.class);
  }

  private static String txn() {
    return CoreCommandsTestSupport.txnId();
  }

  @Test
  void aMixedRunKeepsEveryTransactionBalancedAndTheChargesInTheSystemWallets() {
    long before = total();
    long gp = balance("SETTLEMENT_OCS_GP");
    long npsb = balance("SETTLEMENT_NPSB");

    String p2p = txn();
    handle(cmds.post(p2p, A, B, 100_000));
    String reversed = txn();
    handle(cmds.post(reversed, A, B, 50_000));
    handle(cmds.reverse(txn(), reversed, 50_000));

    String cashOut = txn();
    handle(cmds.command("PostTransfer", cashOut, cashOut + ":post", cmds.transfer("CASH_OUT", A, AGENT, 100_000)));

    String topup = txn();
    Map<String, Object> hold = cmds.transfer("TOPUP", A, A, 100_000);
    hold.put("attributes", Map.of("settlementWallet", "SETTLEMENT_OCS_GP"));
    CoreEvent held = handle(cmds.command("HoldFunds", topup, topup + ":hold", hold));
    handle(cmds.commitHold(topup, json.valueToTree(held.payload()).path("holdId").asString()));

    String released = txn();
    CoreEvent other = handle(cmds.holdFunds(released, B, 30_000));
    handle(cmds.releaseHold(released, json.valueToTree(other.payload()).path("holdId").asString()));

    String credit = txn();
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("txnType", "NPSB_IN");
    in.put("creditParty", new Party("WALLET", B, null, "CUSTOMER"));
    in.put("amount", Amount.bdt(70_000));
    in.put("attributes", Map.of("settlementWallet", "SETTLEMENT_NPSB"));
    handle(cmds.command("CreditWallet", credit, credit + ":credit", in));

    // every txnId sums to zero (FRS 7.3)
    assertThat(jdbc.queryForList(
            "select txn_id from entry group by txn_id "
                + "having sum(case when direction = 'CREDIT' then amount_minor else -amount_minor end) <> 0",
            String.class))
        .isEmpty();
    // the charges: P2P 500 = 435 + 65 VAT (the reversed one gave its fee back); cash out 1000 = 130 VAT + 348
    // agent + 87 distributor + 435 income; top-up 200 = 26 VAT + 174 income
    assertThat(balance(WalletSeeder.FEE_INCOME)).isEqualTo(435 + 435 + 174);
    assertThat(balance(WalletSeeder.VAT_PAYABLE)).isEqualTo(65 + 130 + 26);
    assertThat(balance(WalletSeeder.COMMISSION_PAYABLE)).isEqualTo(348 + 87);
    assertThat(balance(WalletSeeder.FEE_INCOME) + balance(WalletSeeder.VAT_PAYABLE) + balance(WalletSeeder.COMMISSION_PAYABLE))
        .isEqualTo(500 + 1000 + 200);
    // the partners' settlement wallets
    assertThat(balance("SETTLEMENT_OCS_GP")).isEqualTo(gp + 100_000);
    assertThat(balance("SETTLEMENT_NPSB")).isEqualTo(npsb - 70_000);
    assertThat(total()).isEqualTo(before);
    assertThat(jdbc.queryForObject("select coalesce(sum(held_minor), 0) from wallet", Long.class)).isZero();

    // every entry carries the balance after it: the wallet's last entry shows its balance now
    for (String w : List.of(A, B, AGENT, WalletSeeder.FEE_INCOME, WalletSeeder.VAT_PAYABLE, "SETTLEMENT_OCS_GP")) {
      assertThat(jdbc.queryForObject(
              "select balance_after_minor from entry where wallet_id = ? order by id desc limit 1", Long.class, w))
          .as("balance after the last entry of %s", w)
          .isEqualTo(balance(w));
    }
    assertThat(jdbc.queryForObject("select count(*) from entry where balance_after_minor is null", Long.class)).isZero();
  }

  @Test
  void aHoldOrCreditNamingAnUnknownSettlementWalletIsRefused() {
    String topup = txn();
    Map<String, Object> hold = cmds.transfer("TOPUP", A, A, 10_000);
    hold.put("attributes", Map.of("settlementWallet", "SETTLEMENT_NOWHERE"));
    CoreCommand c = cmds.command("HoldFunds", topup, topup + ":hold", hold);
    CoreEvent e = cmds.valid(service.handle(c).orElseThrow(), c);

    assertThat(e.eventType()).isEqualTo("HoldRejected");
    assertThat(e.reasonCode()).isEqualTo("SETTLEMENT_WALLET_NOT_FOUND");
  }
}
