package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.contracts.ContractSchemas;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.Party;
import mfs.mw.core.mock.security.PinKeyProvider;
import mfs.mw.core.mock.security.TestPins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wallet rules the core owns: which wallet types a txnType may move money between (agent cash
 * in / cash out) and the PIN lockout after consecutive wrong PINs (spec 9.1), against a real
 * PostgreSQL ledger. Every event is checked against {@code core.event.v1}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class WalletRulesIT {

  static final String CUSTOMER_A = "01711000001";
  static final String CUSTOMER_B = "01711000002";
  static final String AGENT = "01811000001";
  static final String AGENT_2 = "01811000002";
  static final String MERCHANT = "01911000001";
  static final String ORG = "01311000001";

  private final CoreCommandService service;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final PinKeyProvider keys;
  private final JsonMapper json;

  @Autowired
  WalletRulesIT(
      CoreCommandService service, WalletSeeder seeder, JdbcTemplate jdbc, PinKeyProvider keys, JsonMapper json) {
    this.service = service;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.keys = keys;
    this.json = json;
  }

  @BeforeEach
  void freshLedger() {
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  private CoreCommand command(String commandType, String txnType, String from, String to, long minor, String pin) {
    String u = UUID.randomUUID().toString();
    String txnId = u.substring(0, 14) + "7" + u.substring(15);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("txnType", txnType);
    payload.put("debitParty", new Party("WALLET", from, null, null));
    payload.put("creditParty", new Party("WALLET", to, null, null));
    payload.put("amount", Amount.bdt(minor));
    if (pin != null) {
      payload.put("encryptedPin", TestPins.encrypt(keys.publicKeyPem(), keys.keyId(), pin));
    }
    String suffix = "PostTransfer".equals(commandType) ? ":post" : ":validate";
    Instant now = Instant.now();
    return new CoreCommand(
        1, commandType, txnId + suffix, txnId, "corr-" + txnId, now, now.plusSeconds(300), json.valueToTree(payload));
  }

  private CoreEvent handle(CoreCommand c) {
    CoreEvent e = service.handle(c).orElseThrow();
    assertThat(ContractSchemas.schema("events/mw-core-mock/core.event.v1.schema.json").validate(json.valueToTree(e)))
        .as("event %s against core.event.v1", e.eventType())
        .isEmpty();
    return e;
  }

  private String validate(String txnType, String from, String to, String pin) {
    CoreEvent e = handle(command("ValidateTransaction", txnType, from, to, 1_000, pin));
    return e.outcome() == CoreEvent.Outcome.SUCCESS ? "OK" : e.reasonCode();
  }

  private String status(String wallet) {
    return jdbc.queryForObject("select status from wallet where id = ?", String.class, wallet);
  }

  private long balance(String wallet) {
    return jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, wallet);
  }

  // --- wallet types per txnType -------------------------------------------------------------

  @Test
  void cashInRunsOnlyFromAnAgentToACustomer() {
    assertThat(validate("CASH_IN", AGENT, CUSTOMER_A, "1234")).isEqualTo("OK");
    assertThat(validate("CASH_IN", CUSTOMER_A, CUSTOMER_B, "1234")).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("CASH_IN", AGENT, AGENT_2, "1234")).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("CASH_IN", CUSTOMER_A, AGENT, "1234")).isEqualTo("INVALID_COUNTERPARTY");
  }

  @Test
  void cashOutRunsOnlyFromACustomerToAnAgent() {
    assertThat(validate("CASH_OUT", CUSTOMER_A, AGENT, "1234")).isEqualTo("OK");
    assertThat(validate("CASH_OUT", AGENT, CUSTOMER_A, "1234")).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("CASH_OUT", CUSTOMER_A, CUSTOMER_B, "1234")).isEqualTo("INVALID_COUNTERPARTY");
  }

  @Test
  void merchantPayRunsOnlyFromACustomerToAMerchant() {
    assertThat(validate("MERCHANT_PAY", CUSTOMER_A, MERCHANT, "1234")).isEqualTo("OK");
    assertThat(validate("MERCHANT_PAY", CUSTOMER_A, CUSTOMER_B, "1234")).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("MERCHANT_PAY", CUSTOMER_A, AGENT, "1234")).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("MERCHANT_PAY", AGENT, MERCHANT, "1234")).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("MERCHANT_PAY", MERCHANT, CUSTOMER_A, "1234")).isEqualTo("INVALID_COUNTERPARTY");
  }

  @Test
  void aMerchantPaymentMovesTheAmountToTheMerchantWithoutAFee() {
    CoreEvent e = handle(command("PostTransfer", "MERCHANT_PAY", CUSTOMER_A, MERCHANT, 25_000, "1234"));
    assertThat(e.eventType()).isEqualTo("TransferPosted");
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000 - 25_000);
    assertThat(balance(MERCHANT)).isEqualTo(25_000);
  }

  @Test
  void aWrongCounterpartyIsRefusedOnPostTooAndMovesNoMoney() {
    CoreEvent e = handle(command("PostTransfer", "CASH_OUT", AGENT, CUSTOMER_A, 1_000, "1234"));
    assertThat(e.eventType()).isEqualTo("TransferRejected");
    assertThat(e.reasonCode()).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(balance(AGENT)).isEqualTo(10_000_000);
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000);
  }

  // --- BULK_DISBURSE (ticket 34): org -> customer, authorised by maker-checker, no PIN --------

  @Test
  void bulkDisburseRunsOnlyFromAnOrgToACustomerWithoutAPin() {
    assertThat(validate("BULK_DISBURSE", ORG, CUSTOMER_A, null)).isEqualTo("OK");
    assertThat(validate("BULK_DISBURSE", CUSTOMER_A, CUSTOMER_B, null)).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("BULK_DISBURSE", ORG, AGENT, null)).isEqualTo("INVALID_COUNTERPARTY");
  }

  @Test
  void anOrgPaysOutMoreInADayThanAWalletLimitAllows() {
    long perTxn = 500_000; // the per-transaction cap of every wallet
    for (int i = 0; i < 4; i++) { // 4 x 5,000 taka > the 15,000 taka daily limit of a customer
      CoreEvent e = handle(command("PostTransfer", "BULK_DISBURSE", ORG, CUSTOMER_B, perTxn, null));
      assertThat(e.eventType()).as("payout %d", i).isEqualTo("TransferPosted");
    }
    assertThat(balance(ORG)).isEqualTo(100_000_000 - 4 * perTxn);
    assertThat(balance(CUSTOMER_B)).isEqualTo(1_000_000 + 4 * perTxn);
  }

  @Test
  void typesWithoutARuleAreNotRestricted() {
    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "1234")).isEqualTo("OK");
  }

  @Test
  void aWrongCounterpartyIsDecidedBeforeThePinAndCountsNoPinFailure() {
    for (int i = 0; i < 6; i++) {
      assertThat(validate("CASH_IN", CUSTOMER_A, CUSTOMER_B, "9999")).isEqualTo("INVALID_COUNTERPARTY");
    }
    assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "1234")).isEqualTo("OK");
  }

  // --- PIN lockout --------------------------------------------------------------------------

  @Test
  void fiveConsecutiveWrongPinsLockTheWallet() {
    for (int i = 1; i <= 4; i++) {
      assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "9999")).as("attempt %d", i).isEqualTo("INVALID_PIN");
      assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
    }
    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "9999")).isEqualTo("INVALID_PIN");
    assertThat(status(CUSTOMER_A)).isEqualTo("LOCKED");

    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "1234")).isEqualTo("ACCOUNT_LOCKED");
  }

  @Test
  void aCorrectPinResetsTheCount() {
    for (int i = 0; i < 4; i++) {
      validate("P2P", CUSTOMER_A, CUSTOMER_B, "9999");
    }
    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "1234")).isEqualTo("OK");
    for (int i = 0; i < 4; i++) {
      assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "9999")).isEqualTo("INVALID_PIN");
    }
    assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
  }

  @Test
  void aRepeatedWrongPinCommandCountsOnce() {
    CoreCommand wrong = command("ValidateTransaction", "P2P", CUSTOMER_A, CUSTOMER_B, 1_000, "9999");
    for (int i = 0; i < 6; i++) {
      assertThat(handle(wrong).reasonCode()).isEqualTo("INVALID_PIN");
    }
    assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
  }

  @Test
  void aLockedWalletCanStillReceiveMoney() {
    for (int i = 0; i < 5; i++) {
      validate("P2P", CUSTOMER_A, CUSTOMER_B, "9999");
    }
    assertThat(status(CUSTOMER_A)).isEqualTo("LOCKED");

    CoreEvent e = handle(command("PostTransfer", "P2P", CUSTOMER_B, CUSTOMER_A, 1_000, "1234"));
    assertThat(e.eventType()).isEqualTo("TransferPosted");
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_001_000);
  }

  // --- ops unlock (ticket 52) ---------------------------------------------------------------

  private CoreCommand unlock(String wallet) {
    String u = UUID.randomUUID().toString();
    String txnId = u.substring(0, 14) + "7" + u.substring(15);
    String changeRequestId = UUID.randomUUID().toString();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("subject", Map.of("type", "WALLET", "id", wallet));
    payload.put("changeRequestId", changeRequestId);
    payload.put("makerId", "ops-maker01");
    payload.put("checkerId", "ops-checker01");
    Instant now = Instant.now();
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schemaVersion", 1);
    envelope.put("commandType", "UnlockWallet");
    envelope.put("commandId", changeRequestId + ":unlock");
    envelope.put("txnId", txnId);
    envelope.put("correlationId", "corr-" + txnId);
    envelope.put("issuedAt", now.toString());
    envelope.put("expiresAt", now.plusSeconds(300).toString());
    envelope.put("payload", payload);
    assertThat(ContractSchemas.schema("events/mw-orchestrator/core.command.v1.schema.json").validate(json.valueToTree(envelope)))
        .as("UnlockWallet against core.command.v1")
        .isEmpty();
    return json.convertValue(envelope, CoreCommand.class);
  }

  private void lockOut(String wallet) {
    for (int i = 0; i < 5; i++) {
      validate("P2P", wallet, CUSTOMER_B, "9999");
    }
    assertThat(status(wallet)).isEqualTo("LOCKED");
  }

  @Test
  void anUnlockClearsTheLockAndTheFailureCountOnce() {
    lockOut(CUSTOMER_A);
    CoreCommand unlock = unlock(CUSTOMER_A);

    CoreEvent e = handle(unlock);
    assertThat(e.eventType()).isEqualTo("WalletUnlocked");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.SUCCESS);
    assertThat(e.commandId()).isEqualTo(unlock.commandId());
    assertThat(e.payload()).containsEntry("walletId", CUSTOMER_A);
    assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("select pin_failures from wallet where id = ?", Integer.class, CUSTOMER_A)).isZero();
    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "1234")).isEqualTo("OK");

    // lock it again: a replay of the same command answers from the log and changes nothing
    lockOut(CUSTOMER_A);
    CoreEvent replay = handle(unlock);
    assertThat(replay.eventId()).isEqualTo(e.eventId());
    assertThat(replay.eventType()).isEqualTo("WalletUnlocked");
    assertThat(status(CUSTOMER_A)).isEqualTo("LOCKED");
  }

  @Test
  void anUnlockAfterTheUnlockCountsFailuresAfresh() {
    lockOut(CUSTOMER_A);
    handle(unlock(CUSTOMER_A));
    for (int i = 0; i < 4; i++) {
      assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B, "9999")).isEqualTo("INVALID_PIN");
    }
    assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
  }

  @Test
  void anUnlockOfAWalletThatIsNotLockedIsRejected() {
    validate("P2P", CUSTOMER_A, CUSTOMER_B, "9999");

    CoreEvent e = handle(unlock(CUSTOMER_A));
    assertThat(e.eventType()).isEqualTo("UnlockRejected");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.FAILURE);
    assertThat(e.reasonCode()).isEqualTo("WALLET_NOT_LOCKED");
    assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("select pin_failures from wallet where id = ?", Integer.class, CUSTOMER_A)).isEqualTo(1);
  }

  @Test
  void anUnlockOfAnUnknownWalletIsRejected() {
    CoreEvent e = handle(unlock("01799999999"));
    assertThat(e.eventType()).isEqualTo("UnlockRejected");
    assertThat(e.reasonCode()).isEqualTo("WALLET_NOT_FOUND");
  }

  @Test
  void anUnlockDoesNotLiftAComplianceBlock() {
    jdbc.update("update wallet set status = 'BLOCKED' where id = ?", CUSTOMER_A);

    CoreEvent e = handle(unlock(CUSTOMER_A));
    assertThat(e.reasonCode()).isEqualTo("WALLET_NOT_LOCKED");
    assertThat(status(CUSTOMER_A)).isEqualTo("BLOCKED");
  }
}
