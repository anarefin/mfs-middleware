package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Random;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.security.PinKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract tests per command (spec WP06, 6.3) for HoldFunds, CommitHold, ReleaseHold, CreditWallet,
 * ReverseTransaction and QueryTransaction: success and failure event, ledger effect and the balance
 * invariant, against a real PostgreSQL ledger. Every event is validated against {@code core.event.v1}
 * and every command against {@code core.command.v1}. TOPUP carries a 200 poisha fee in the config.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class CoreCommandsIT {

  static final String A = "01711000001";
  static final String B = "01711000002";
  static final String BROKE = "01911000001";
  static final long TOPUP_FEE = 200;
  static final long P2P_FEE = 500;

  private final CoreCommandService service;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final CoreCommandsTestSupport cmds;

  @Autowired
  CoreCommandsIT(
      CoreCommandService service, WalletSeeder seeder, JdbcTemplate jdbc, PinKeyProvider keys, JsonMapper json) {
    this.service = service;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
    this.cmds = new CoreCommandsTestSupport(keys, json);
  }

  @BeforeEach
  void freshLedger() {
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  private static String txn() {
    return CoreCommandsTestSupport.txnId();
  }

  private CoreEvent handle(CoreCommand c) {
    return cmds.valid(service.handle(c).orElseThrow(), c);
  }

  private long balance(String id) {
    return jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, id);
  }

  private long held(String id) {
    return jdbc.queryForObject("select held_minor from wallet where id = ?", Long.class, id);
  }

  private long total() {
    return jdbc.queryForObject("select sum(balance_minor) from wallet", Long.class);
  }

  private String holdId(CoreEvent funds) {
    return json.valueToTree(funds.payload()).path("holdId").asString();
  }

  private String status(CoreEvent statusEvent) {
    return json.valueToTree(statusEvent.payload()).path("txnStatus").asString();
  }

  private void assertInvariants() {
    assertThat(jdbc.queryForObject("select coalesce(sum(amount_minor),0) from entry where direction = 'DEBIT'", Long.class))
        .isEqualTo(jdbc.queryForObject("select coalesce(sum(amount_minor),0) from entry where direction = 'CREDIT'", Long.class));
    assertThat(jdbc.queryForObject("select count(*) from wallet where held_minor > balance_minor or balance_minor < 0", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("select coalesce(sum(held_minor),0) from wallet", Long.class))
        .isEqualTo(jdbc.queryForObject("select coalesce(sum(amount_minor + fee_minor),0) from hold where status = 'HELD'", Long.class));
  }

  // --- BILL_PAY: the credit side is outside the core (ticket 21) -----------------------------

  private CoreCommand billPay(String type, String step, String txn, long minor) {
    var p = cmds.transfer("BILL_PAY", A, A, minor, "1234");
    p.put("creditParty", new mfs.mw.core.mock.dto.Party("BILLER_ACCOUNT", "DESCO-1234567", null, null));
    return cmds.command(type, txn, txn + ":" + step, p);
  }

  @Test
  void aBillPayToABillerAccountValidatesTheDebitSideOnlyWithTheBillPayFee() {
    CoreEvent e = handle(billPay("ValidateTransaction", "validate", txn(), 50_000));
    assertThat(e.eventType()).isEqualTo("TransactionValidated");
    assertThat(json.valueToTree(e.payload()).path("fee").path("valueMinor").asLong()).isEqualTo(300);
  }

  @Test
  void aBillPayToABillerAccountIsHeldAndNeverPostedDirectly() {
    String txn = txn();
    CoreEvent held = handle(billPay("HoldFunds", "hold", txn, 50_000));
    assertThat(held.eventType()).isEqualTo("FundsHeld");
    assertThat(held(A)).isEqualTo(50_300);
    CoreEvent post = handle(billPay("PostTransfer", "post", txn(), 50_000));
    assertThat(post.eventType()).isEqualTo("TransferRejected");
    assertThat(post.reasonCode()).isEqualTo("CREDIT_WALLET_NOT_FOUND");
  }

  // --- TOPUP: the credit side is the mobile number to recharge, not a wallet (ticket 17) ----

  private CoreCommand topupValidate(String txn, String msisdn) {
    var p = cmds.transfer("TOPUP", A, A, 2_000, "1234");
    p.put("creditParty", new mfs.mw.core.mock.dto.Party("MSISDN", msisdn, null, null));
    return cmds.command("ValidateTransaction", txn, txn + ":validate", p);
  }

  @Test
  void aTopupOfThePayersOwnNumberValidatesTheDebitSideOnly() {
    CoreEvent e = handle(topupValidate(txn(), A));
    assertThat(e.eventType()).isEqualTo("TransactionValidated");
    assertThat(json.valueToTree(e.payload()).path("fee").path("valueMinor").asLong()).isEqualTo(200);
  }

  @Test
  void aTopupOfANumberWithoutAWalletValidates() {
    assertThat(handle(topupValidate(txn(), "01799999999")).eventType()).isEqualTo("TransactionValidated");
  }

  // --- ADD_MONEY_BANK: the debit side is outside the core (ticket 23) -----------------------

  private CoreCommand addMoney(String txn, String payee, long minor, String pin) {
    var p = cmds.transfer("ADD_MONEY_BANK", A, payee, minor, pin);
    p.put("debitParty", new mfs.mw.core.mock.dto.Party("BANK_ACCOUNT", "1234567890123", null, null));
    return cmds.command("ValidateTransaction", txn, txn + ":validate", p);
  }

  @Test
  void anAddMoneyFromABankAccountValidatesThePayeeWalletAndItsPinAndMovesNothing() {
    long before = balance(A);
    CoreEvent e = handle(addMoney(txn(), A, 50_000, "1234"));
    assertThat(e.eventType()).isEqualTo("TransactionValidated");
    assertThat(json.valueToTree(e.payload()).path("fee").path("valueMinor").asLong()).isZero();
    assertThat(balance(A)).isEqualTo(before);
    assertThat(held(A)).isZero();
  }

  @Test
  void anAddMoneyIsRefusedForAWrongPinOfThePayeeWallet() {
    CoreEvent e = handle(addMoney(txn(), A, 50_000, "9999"));
    assertThat(e.eventType()).isEqualTo("TransactionRejected");
    assertThat(e.reasonCode()).isEqualTo("INVALID_PIN");
    assertThat(jdbc.queryForObject("select pin_failures from wallet where id = ?", Integer.class, A)).isEqualTo(1);
  }

  @Test
  void anAddMoneyToAnUnknownWalletIsRefused() {
    CoreEvent e = handle(addMoney(txn(), "01799999999", 50_000, "1234"));
    assertThat(e.eventType()).isEqualTo("TransactionRejected");
    assertThat(e.reasonCode()).isEqualTo("CREDIT_WALLET_NOT_FOUND");
  }

  @Test
  void anAddMoneyAboveThePerTransactionLimitIsRefusedWhateverTheWalletHolds() {
    CoreEvent e = handle(addMoney(txn(), A, 100_000_000, "1234"));
    assertThat(e.eventType()).isEqualTo("TransactionRejected");
    assertThat(e.reasonCode()).isEqualTo("LIMIT_EXCEEDED_PER_TXN");
  }

  @Test
  void anAddMoneyIsNeverPostedDirectlyFromABankAccount() {
    String txn = txn();
    var p = cmds.transfer("ADD_MONEY_BANK", A, A, 50_000, "1234");
    p.put("debitParty", new mfs.mw.core.mock.dto.Party("BANK_ACCOUNT", "1234567890123", null, null));
    CoreEvent post = handle(cmds.command("PostTransfer", txn, txn + ":post", p));
    assertThat(post.eventType()).isEqualTo("TransferRejected");
    assertThat(post.reasonCode()).isEqualTo("WALLET_NOT_FOUND");
  }

  // --- HoldFunds / CommitHold / ReleaseHold -------------------------------------------------

  @Test
  void holdReducesAvailableBalanceButNotTheLedgerBalance() {
    long before = total();

    CoreEvent e = handle(cmds.holdFunds(txn(), A, 100_000, "1234"));

    assertThat(e.eventType()).isEqualTo("FundsHeld");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.SUCCESS);
    assertThat(e.coreRef()).startsWith("CORE-");
    assertThat(balance(A)).isEqualTo(1_000_000);
    assertThat(held(A)).isEqualTo(100_000 + TOPUP_FEE);
    assertThat(total()).isEqualTo(before);
    assertThat(jdbc.queryForObject("select status from hold where id = ?", String.class, holdId(e))).isEqualTo("HELD");
    assertInvariants();
  }

  @Test
  void aHoldCountsAgainstWhatCanBeSpent() {
    handle(cmds.holdFunds(txn(), A, 400_000, "1234"));
    handle(cmds.holdFunds(txn(), A, 400_000, "1234"));

    CoreEvent third = handle(cmds.holdFunds(txn(), A, 400_000, "1234"));
    assertThat(third.eventType()).isEqualTo("HoldRejected");
    assertThat(third.outcome()).isEqualTo(CoreEvent.Outcome.FAILURE);
    assertThat(third.reasonCode()).isEqualTo("INSUFFICIENT_BALANCE");

    CoreEvent transfer = handle(cmds.post(txn(), A, B, 300_000, "1234"));
    assertThat(transfer.reasonCode()).isEqualTo("INSUFFICIENT_BALANCE");
    assertInvariants();
  }

  @Test
  void holdIsRejectedForWrongPinUnknownWalletAndBadAmount() {
    CoreEvent pin = handle(cmds.holdFunds(txn(), A, 1_000, "0000"));
    assertThat(pin.eventType()).isEqualTo("HoldRejected");
    assertThat(pin.reasonCode()).isEqualTo("INVALID_PIN");
    assertThat(handle(cmds.holdFunds(txn(), "01700000000", 1_000, "1234")).reasonCode()).isEqualTo("WALLET_NOT_FOUND");
    assertThat(handle(cmds.holdFunds(txn(), A, 0, "1234")).reasonCode()).isEqualTo("INVALID_AMOUNT");
    assertThat(held(A)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from hold", Long.class)).isZero();
  }

  @Test
  void commitMovesTheMoneyAndTheFeeAndFreesTheHold() {
    long before = total();
    String txn = txn();
    String hold = holdId(handle(cmds.holdFunds(txn, A, 100_000, "1234")));

    CoreEvent e = handle(cmds.commitHold(txn, hold));

    assertThat(e.eventType()).isEqualTo("HoldCommitted");
    assertThat(balance(A)).isEqualTo(1_000_000 - 100_000 - TOPUP_FEE);
    assertThat(held(A)).isZero();
    assertThat(balance(WalletSeeder.SETTLEMENT_WALLET)).isEqualTo(settlementSeed() + 100_000);
    assertThat(balance(WalletSeeder.FEE_WALLET)).isEqualTo(TOPUP_FEE);
    assertThat(jdbc.queryForObject("select status from hold where id = ?", String.class, hold)).isEqualTo("COMMITTED");
    assertThat(total()).isEqualTo(before);
    assertInvariants();
  }

  private long settlementSeed() {
    return WalletSeeder.SETTLEMENT_SEED_MINOR;
  }

  @Test
  void commitIsRejectedForUnknownOrReleasedHoldsAndARepeatedCommitIsHarmless() {
    CoreEvent unknown = handle(cmds.commitHold(txn(), "HOLD-NOPE"));
    assertThat(unknown.eventType()).isEqualTo("CommitFailed");
    assertThat(unknown.outcome()).isEqualTo(CoreEvent.Outcome.FAILURE);
    assertThat(unknown.reasonCode()).isEqualTo("HOLD_NOT_FOUND");

    String txn2 = txn();
    String released = holdId(handle(cmds.holdFunds(txn2, A, 10_000, "1234")));
    handle(cmds.releaseHold(txn2, released));
    assertThat(handle(cmds.commitHold(txn2, released)).reasonCode()).isEqualTo("HOLD_RELEASED");

    String txn3 = txn();
    String committed = holdId(handle(cmds.holdFunds(txn3, A, 10_000, "1234")));
    handle(cmds.commitHold(txn3, committed));
    long after = balance(A);
    CoreCommand again = cmds.command("CommitHold", txn3, txn3 + ":commit-2", Map.of("holdId", committed));
    assertThat(handle(again).eventType()).isEqualTo("HoldCommitted");
    assertThat(balance(A)).isEqualTo(after);
    assertInvariants();
  }

  @Test
  void releaseRestoresAvailabilityAndMovesNothing() {
    long before = total();
    String txn = txn();
    String hold = holdId(handle(cmds.holdFunds(txn, A, 100_000, "1234")));

    CoreEvent e = handle(cmds.releaseHold(txn, hold));

    assertThat(e.eventType()).isEqualTo("HoldReleased");
    assertThat(held(A)).isZero();
    assertThat(balance(A)).isEqualTo(1_000_000);
    assertThat(jdbc.queryForObject("select count(*) from entry", Long.class)).isZero();
    assertThat(total()).isEqualTo(before);
    handle(cmds.holdFunds(txn(), A, 400_000, "1234"));
    handle(cmds.holdFunds(txn(), A, 400_000, "1234")); // 800,400 held: only the release made room below
    assertThat(handle(cmds.post(txn(), A, B, 190_000, "1234")).eventType()).isEqualTo("TransferPosted");
    assertInvariants();
  }

  @Test
  void releaseIsRejectedForUnknownAndCommittedHoldsAndRepeatedReleaseIsHarmless() {
    CoreEvent unknown = handle(cmds.releaseHold(txn(), "HOLD-NOPE"));
    assertThat(unknown.eventType()).isEqualTo("ReleaseFailed");
    assertThat(unknown.reasonCode()).isEqualTo("HOLD_NOT_FOUND");

    String txn2 = txn();
    String committed = holdId(handle(cmds.holdFunds(txn2, A, 10_000, "1234")));
    handle(cmds.commitHold(txn2, committed));
    CoreEvent e = handle(cmds.releaseHold(txn2, committed));
    assertThat(e.eventType()).isEqualTo("ReleaseFailed");
    assertThat(e.reasonCode()).isEqualTo("HOLD_ALREADY_COMMITTED");

    String txn3 = txn();
    String released = holdId(handle(cmds.holdFunds(txn3, A, 10_000, "1234")));
    handle(cmds.releaseHold(txn3, released));
    CoreCommand again = cmds.command("ReleaseHold", txn3, txn3 + ":release-2", Map.of("holdId", released));
    assertThat(handle(again).eventType()).isEqualTo("HoldReleased");
    assertInvariants();
  }

  // --- CreditWallet -------------------------------------------------------------------------

  @Test
  void creditWalletAddsMoneyFromSettlementAndReportsTheBalance() {
    long before = total();

    CoreEvent e = handle(cmds.creditWallet(txn(), A, 250_000));

    assertThat(e.eventType()).isEqualTo("WalletCredited");
    assertThat(balance(A)).isEqualTo(1_250_000);
    assertThat(e.balancesAfter()).hasSize(1);
    assertThat(e.balancesAfter().get(0).walletId()).isEqualTo(A);
    assertThat(e.balancesAfter().get(0).balance().valueMinor()).isEqualTo(1_250_000);
    assertThat(total()).isEqualTo(before);
    assertInvariants();
  }

  @Test
  void creditWalletIsRejectedForUnknownBlockedWalletsAndBadAmount() {
    assertThat(handle(cmds.creditWallet(txn(), "01700000000", 100)).eventType()).isEqualTo("CreditRejected");
    assertThat(handle(cmds.creditWallet(txn(), "01700000000", 100)).reasonCode()).isEqualTo("CREDIT_WALLET_NOT_FOUND");
    jdbc.update("update wallet set status = 'BLOCKED' where id = ?", B);
    assertThat(handle(cmds.creditWallet(txn(), B, 100)).reasonCode()).isEqualTo("CREDIT_ACCOUNT_BLOCKED");
    assertThat(handle(cmds.creditWallet(txn(), A, 0)).reasonCode()).isEqualTo("INVALID_AMOUNT");
    assertThat(balance(A)).isEqualTo(1_000_000);
  }

  // --- ReverseTransaction -------------------------------------------------------------------

  @Test
  void reversingAPostedTransferGivesEverythingBackIncludingTheFee() {
    long before = total();
    String original = txn();
    handle(cmds.post(original, A, B, 100_000, "1234"));

    CoreEvent e = handle(cmds.reverse(txn(), original, 100_000));

    assertThat(e.eventType()).isEqualTo("TransactionReversed");
    assertThat(balance(A)).isEqualTo(1_000_000);
    assertThat(balance(B)).isEqualTo(1_000_000);
    assertThat(balance(WalletSeeder.FEE_WALLET)).isZero();
    assertThat(total()).isEqualTo(before);
    assertInvariants();
  }

  @Test
  void reversingACommittedHoldGivesTheMoneyBack() {
    String original = txn();
    handle(cmds.commitHold(original, holdId(handle(cmds.holdFunds(original, A, 50_000, "1234")))));

    assertThat(handle(cmds.reverse(txn(), original, 50_000)).eventType()).isEqualTo("TransactionReversed");
    assertThat(balance(A)).isEqualTo(1_000_000);
    assertInvariants();
  }

  @Test
  void reversalIsRejectedForUnknownRepeatedMismatchedAndUnaffordableCases() {
    assertThat(handle(cmds.reverse(txn(), txn(), 100)).eventType()).isEqualTo("ReversalRejected");
    assertThat(handle(cmds.reverse(txn(), txn(), 100)).reasonCode()).isEqualTo("ORIGINAL_NOT_FOUND");

    String original = txn();
    handle(cmds.post(original, A, B, 100_000, "1234"));
    assertThat(handle(cmds.reverse(txn(), original, 99_999)).reasonCode()).isEqualTo("AMOUNT_MISMATCH");
    handle(cmds.reverse(txn(), original, 100_000));
    assertThat(handle(cmds.reverse(txn(), original, 100_000)).reasonCode()).isEqualTo("ALREADY_REVERSED");

    String spent = txn();
    handle(cmds.post(spent, A, BROKE, 100_000, "1234"));
    handle(cmds.post(txn(), BROKE, B, 99_000, "1234")); // the receiver spent what it got
    CoreEvent e = handle(cmds.reverse(txn(), spent, 100_000));
    assertThat(e.eventType()).isEqualTo("ReversalRejected");
    assertThat(e.reasonCode()).isEqualTo("INSUFFICIENT_BALANCE");
    assertInvariants();
  }

  @Test
  void aReversalCannotBeReversedAndAHeldTransactionIsNotYetReversible() {
    String original = txn();
    handle(cmds.post(original, A, B, 1_000, "1234"));
    String reversal = txn();
    handle(cmds.reverse(reversal, original, 1_000));
    assertThat(handle(cmds.reverse(txn(), reversal, 1_000)).reasonCode()).isEqualTo("ORIGINAL_NOT_REVERSIBLE");

    String held = txn();
    handle(cmds.holdFunds(held, A, 1_000, "1234"));
    assertThat(handle(cmds.reverse(txn(), held, 1_000)).reasonCode()).isEqualTo("ORIGINAL_NOT_FOUND");
  }

  // --- QueryTransaction ---------------------------------------------------------------------

  @Test
  void queryReportsTheCoresViewAcrossTheLifecycle() {
    String unknown = txn();
    CoreEvent notFound = handle(cmds.query(unknown));
    assertThat(notFound.eventType()).isEqualTo("TransactionStatus");
    assertThat(notFound.outcome()).isEqualTo(CoreEvent.Outcome.SUCCESS);
    assertThat(status(notFound)).isEqualTo("NOT_FOUND");

    String txn = txn();
    CoreEvent funds = handle(cmds.holdFunds(txn, A, 1_000, "1234"));
    CoreEvent held = handle(cmds.query(txn));
    assertThat(status(held)).isEqualTo("HELD");
    assertThat(json.valueToTree(held.payload()).path("holdId").asString()).isEqualTo(holdId(funds));
    handle(cmds.commitHold(txn, holdId(funds)));
    assertThat(status(handle(cmds.query(txn)))).isEqualTo("COMMITTED");
    handle(cmds.reverse(txn(), txn, 1_000));
    assertThat(status(handle(cmds.query(txn)))).isEqualTo("REVERSED");

    String released = txn();
    handle(cmds.releaseHold(released, holdId(handle(cmds.holdFunds(released, A, 1_000, "1234")))));
    assertThat(status(handle(cmds.query(released)))).isEqualTo("RELEASED");

    String posted = txn();
    handle(cmds.post(posted, A, B, 1_000, "1234"));
    assertThat(status(handle(cmds.query(posted)))).isEqualTo("POSTED");

    String credited = txn();
    handle(cmds.creditWallet(credited, A, 1_000));
    assertThat(status(handle(cmds.query(credited)))).isEqualTo("CREDITED");

    String validated = txn();
    handle(cmds.validate(validated, A, B, 1_000, "1234"));
    assertThat(status(handle(cmds.query(validated)))).isEqualTo("VALIDATED");

    String rejected = txn();
    handle(cmds.post(rejected, A, B, 1_000, "0000"));
    assertThat(status(handle(cmds.query(rejected)))).isEqualTo("REJECTED");
  }

  @Test
  void queryNarrowedToACommandAnswersForThatCommandOnly() {
    String txn = txn();
    CoreCommand validate = cmds.validate(txn, A, B, 1_000, "1234");
    handle(validate);
    handle(cmds.post(txn, A, B, 1_000, "1234"));

    assertThat(status(handle(cmds.queryCommand(txn, validate.commandId())))).isEqualTo("VALIDATED");
    assertThat(status(handle(cmds.queryCommand(txn, "never-seen")))).isEqualTo("NOT_FOUND");
  }

  // --- invariants across sequences ----------------------------------------------------------

  @Test
  void balancesAndDoubleEntryHoldAcrossRandomHoldCommitReleaseReverseSequences() {
    long before = total();
    Random random = new Random(7);
    List<String> wallets = List.of(A, B, BROKE, "01811000001", "01711000003");
    List<String> posted = new java.util.ArrayList<>();
    List<String[]> open = new java.util.ArrayList<>();
    for (int i = 0; i < 200; i++) {
      String w = wallets.get(random.nextInt(wallets.size()));
      switch (random.nextInt(6)) {
        case 0 -> {
          String txn = txn();
          CoreEvent e = handle(cmds.holdFunds(txn, w, 1 + random.nextInt(300_000), "1234"));
          if ("FundsHeld".equals(e.eventType())) {
            open.add(new String[] {txn, holdId(e)});
          }
        }
        case 1 -> {
          if (!open.isEmpty()) {
            String[] h = open.remove(random.nextInt(open.size()));
            handle(cmds.commitHold(h[0], h[1]));
            posted.add(h[0]);
          }
        }
        case 2 -> {
          if (!open.isEmpty()) {
            String[] h = open.remove(random.nextInt(open.size()));
            handle(cmds.releaseHold(h[0], h[1]));
          }
        }
        case 3 -> {
          String txn = txn();
          if ("TransferPosted".equals(handle(cmds.post(txn, w, wallets.get(random.nextInt(wallets.size())), 1 + random.nextInt(300_000), "1234")).eventType())) {
            posted.add(txn);
          }
        }
        case 4 -> handle(cmds.creditWallet(txn(), w, 1 + random.nextInt(100_000)));
        default -> {
          if (!posted.isEmpty()) {
            String original = posted.remove(random.nextInt(posted.size()));
            long amount = jdbc.queryForObject("select sum(amount_minor) from entry where txn_id = ? and kind = 'TRANSFER' and direction = 'DEBIT'", Long.class, original);
            handle(cmds.reverse(txn(), original, amount));
          }
        }
      }
      assertInvariants();
    }
    assertThat(jdbc.queryForObject("select count(*) from outbox", Long.class)).isGreaterThan(100);
    // credits draw on the settlement wallet, so the sum of all balances never changes
    assertThat(total()).isEqualTo(before);
    assertThat(P2P_FEE).isPositive();
  }
}
