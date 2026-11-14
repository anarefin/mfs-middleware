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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wallet rules the core owns: which wallet types a txnType may move money between (agent cash
 * in / cash out), a core-side lock (status LOCKED; the PIN lockout moved to mw-auth in ticket 72)
 * and its ops unlock, against a real PostgreSQL ledger. Transfers carry mw-auth's assertion. Every event is checked against {@code core.event.v1}.
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
  private final JsonMapper json;

  @Autowired
  WalletRulesIT(
      CoreCommandService service, WalletSeeder seeder, JdbcTemplate jdbc, JsonMapper json) {
    this.service = service;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void freshLedger() {
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  private CoreCommand command(String commandType, String txnType, String from, String to, long minor) {
    String u = UUID.randomUUID().toString();
    String txnId = u.substring(0, 14) + "7" + u.substring(15);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("txnType", txnType);
    payload.put("debitParty", new Party("WALLET", from, null, null));
    payload.put("creditParty", new Party("WALLET", to, null, null));
    payload.put("amount", Amount.bdt(minor));
    if (!"BULK_DISBURSE".equals(txnType)) { // a bulk child carries no assertion (maker-checker)
      CoreCommandsTestSupport.authorised(payload, from);
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

  private String validate(String txnType, String from, String to) {
    CoreEvent e = handle(command("ValidateTransaction", txnType, from, to, 1_000));
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
    assertThat(validate("CASH_IN", AGENT, CUSTOMER_A)).isEqualTo("OK");
    assertThat(validate("CASH_IN", CUSTOMER_A, CUSTOMER_B)).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("CASH_IN", AGENT, AGENT_2)).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("CASH_IN", CUSTOMER_A, AGENT)).isEqualTo("INVALID_COUNTERPARTY");
  }

  @Test
  void cashOutRunsOnlyFromACustomerToAnAgent() {
    assertThat(validate("CASH_OUT", CUSTOMER_A, AGENT)).isEqualTo("OK");
    assertThat(validate("CASH_OUT", AGENT, CUSTOMER_A)).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("CASH_OUT", CUSTOMER_A, CUSTOMER_B)).isEqualTo("INVALID_COUNTERPARTY");
  }

  @Test
  void merchantPayRunsOnlyFromACustomerToAMerchant() {
    assertThat(validate("MERCHANT_PAY", CUSTOMER_A, MERCHANT)).isEqualTo("OK");
    assertThat(validate("MERCHANT_PAY", CUSTOMER_A, CUSTOMER_B)).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("MERCHANT_PAY", CUSTOMER_A, AGENT)).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("MERCHANT_PAY", AGENT, MERCHANT)).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("MERCHANT_PAY", MERCHANT, CUSTOMER_A)).isEqualTo("INVALID_COUNTERPARTY");
  }

  @Test
  void aMerchantPaymentMovesTheAmountToTheMerchantWithoutAFee() {
    CoreEvent e = handle(command("PostTransfer", "MERCHANT_PAY", CUSTOMER_A, MERCHANT, 25_000));
    assertThat(e.eventType()).isEqualTo("TransferPosted");
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000 - 25_000);
    assertThat(balance(MERCHANT)).isEqualTo(25_000);
  }

  @Test
  void aWrongCounterpartyIsRefusedOnPostTooAndMovesNoMoney() {
    CoreEvent e = handle(command("PostTransfer", "CASH_OUT", AGENT, CUSTOMER_A, 1_000));
    assertThat(e.eventType()).isEqualTo("TransferRejected");
    assertThat(e.reasonCode()).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(balance(AGENT)).isEqualTo(10_000_000);
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000);
  }

  // --- BULK_DISBURSE (ticket 34): org -> customer, authorised by maker-checker, no assertion -

  @Test
  void bulkDisburseRunsOnlyFromAnOrgToACustomerWithoutAnAssertion() {
    assertThat(validate("BULK_DISBURSE", ORG, CUSTOMER_A)).isEqualTo("OK");
    assertThat(validate("BULK_DISBURSE", CUSTOMER_A, CUSTOMER_B)).isEqualTo("INVALID_COUNTERPARTY");
    assertThat(validate("BULK_DISBURSE", ORG, AGENT)).isEqualTo("INVALID_COUNTERPARTY");
  }

  @Test
  void anOrgPaysOutMoreInADayThanAWalletLimitAllows() {
    long perTxn = 500_000; // the per-transaction cap of every wallet
    for (int i = 0; i < 4; i++) { // 4 x 5,000 taka > the 15,000 taka daily limit of a customer
      CoreEvent e = handle(command("PostTransfer", "BULK_DISBURSE", ORG, CUSTOMER_B, perTxn));
      assertThat(e.eventType()).as("payout %d", i).isEqualTo("TransferPosted");
    }
    assertThat(balance(ORG)).isEqualTo(100_000_000 - 4 * perTxn);
    assertThat(balance(CUSTOMER_B)).isEqualTo(1_000_000 + 4 * perTxn);
  }

  @Test
  void typesWithoutARuleAreNotRestricted() {
    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B)).isEqualTo("OK");
  }

  @Test
  void aWrongCounterpartyIsDecidedBeforeTheAssertion() {
    Integer claimed = jdbc.queryForObject("select count(*) from used_assertion", Integer.class);
    CoreCommand c = command("ValidateTransaction", "CASH_IN", CUSTOMER_A, CUSTOMER_B, 1_000);
    assertThat(handle(c).reasonCode()).isEqualTo("INVALID_COUNTERPARTY");
    // the assertion was not looked at, so its jti is not claimed
    assertThat(jdbc.queryForObject("select count(*) from used_assertion", Integer.class)).isEqualTo(claimed);
  }

  // --- core-side lock (ticket 72: the core keeps no PIN lockout; mw-auth owns it) --------------

  @Test
  void aLockedWalletIsRefusedAsPayerBeforeItsAssertion() {
    lockOut(CUSTOMER_A);
    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B)).isEqualTo("ACCOUNT_LOCKED");
    assertThat(status(CUSTOMER_A)).isEqualTo("LOCKED");
  }

  @Test
  void aLockedWalletCanStillReceiveMoney() {
    lockOut(CUSTOMER_A);

    CoreEvent e = handle(command("PostTransfer", "P2P", CUSTOMER_B, CUSTOMER_A, 1_000));
    assertThat(e.eventType()).isEqualTo("TransferPosted");
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_001_000);
  }

  // --- VerifyCustomerFactors (ticket 70) -----------------------------------------------------

  private CoreCommand factors(String wallet, String resetId) {
    String u = UUID.randomUUID().toString();
    String txnId = u.substring(0, 14) + "7" + u.substring(15);
    Instant now = Instant.now();
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schemaVersion", 1);
    envelope.put("commandType", "VerifyCustomerFactors");
    envelope.put("commandId", "pin-reset:" + resetId);
    envelope.put("txnId", txnId);
    envelope.put("correlationId", "corr-" + txnId);
    envelope.put("issuedAt", now.toString());
    envelope.put("expiresAt", now.plusSeconds(300).toString());
    envelope.put("payload", Map.of("subject", Map.of("type", "WALLET", "id", wallet), "resetId", resetId,
        "factors", Map.of("nidLast4Token", "NID_MZXW6YTB", "dateOfBirthToken", "NID_ONSWG4TF", "tokenKid",
            "tokenise-nid.v1")));
    assertThat(ContractSchemas.schema("events/mw-orchestrator/core.command.v1.schema.json").validate(json.valueToTree(envelope)))
        .as("VerifyCustomerFactors against core.command.v1")
        .isEmpty();
    return json.convertValue(envelope, CoreCommand.class);
  }

  private void assertCoreEvent(CoreEvent e) {
    assertThat(ContractSchemas.schema("events/mw-core-mock/core.event.v1.schema.json")
        .validate(json.readTree(json.writeValueAsString(e)))).as(e.eventType()).isEmpty();
  }

  @Test
  void customerFactorsOfAnActiveOrLockedWalletAreVerified() {
    String reset = UUID.randomUUID().toString();
    CoreEvent e = handle(factors(CUSTOMER_A, reset));
    assertThat(e.eventType()).isEqualTo("CustomerFactorsVerified");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.SUCCESS);
    assertThat(e.payload()).containsEntry("resetId", reset).containsEntry("walletId", CUSTOMER_A);
    assertCoreEvent(e);

    lockOut(CUSTOMER_A);
    assertThat(handle(factors(CUSTOMER_A, UUID.randomUUID().toString())).eventType())
        .isEqualTo("CustomerFactorsVerified");
  }

  @Test
  void customerFactorsOfAnUnknownOrBlockedWalletAreRejected() {
    CoreEvent unknown = handle(factors("01799999998", UUID.randomUUID().toString()));
    assertThat(unknown.eventType()).isEqualTo("CustomerFactorsRejected");
    assertThat(unknown.reasonCode()).isEqualTo("WALLET_NOT_FOUND");
    assertCoreEvent(unknown);

    jdbc.update("update wallet set status = 'BLOCKED' where id = ?", CUSTOMER_A);
    CoreEvent blocked = handle(factors(CUSTOMER_A, UUID.randomUUID().toString()));
    assertThat(blocked.reasonCode()).isEqualTo("WALLET_NOT_ACTIVE");
    assertThat(blocked.payload()).containsKey("resetId");
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

  /** A core-side lock, as a real core sets it for its own reasons (the mock has no API for it). */
  private void lockOut(String wallet) {
    jdbc.update("update wallet set status = 'LOCKED' where id = ?", wallet);
    assertThat(status(wallet)).isEqualTo("LOCKED");
  }

  @Test
  void anUnlockLiftsTheLockOnce() {
    lockOut(CUSTOMER_A);
    CoreCommand unlock = unlock(CUSTOMER_A);

    CoreEvent e = handle(unlock);
    assertThat(e.eventType()).isEqualTo("WalletUnlocked");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.SUCCESS);
    assertThat(e.commandId()).isEqualTo(unlock.commandId());
    assertThat(e.payload()).containsEntry("walletId", CUSTOMER_A);
    assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
    assertThat(validate("P2P", CUSTOMER_A, CUSTOMER_B)).isEqualTo("OK");

    // lock it again: a replay of the same command answers from the log and changes nothing
    lockOut(CUSTOMER_A);
    CoreEvent replay = handle(unlock);
    assertThat(replay.eventId()).isEqualTo(e.eventId());
    assertThat(replay.eventType()).isEqualTo("WalletUnlocked");
    assertThat(status(CUSTOMER_A)).isEqualTo("LOCKED");
  }

  @Test
  void anUnlockOfAWalletThatIsNotLockedIsRejected() {
    CoreEvent e = handle(unlock(CUSTOMER_A));
    assertThat(e.eventType()).isEqualTo("UnlockRejected");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.FAILURE);
    assertThat(e.reasonCode()).isEqualTo("WALLET_NOT_LOCKED");
    assertThat(status(CUSTOMER_A)).isEqualTo("ACTIVE");
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
