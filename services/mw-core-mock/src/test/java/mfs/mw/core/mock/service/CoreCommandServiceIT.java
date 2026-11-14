package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Random;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract tests per command (spec WP06): ValidateTransaction and PostTransfer against a real
 * PostgreSQL ledger. Every event the service emits is checked against the {@code core.event.v1}
 * contract, which core-mock owns.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class CoreCommandServiceIT {

  static final String EVENT_SCHEMA = "events/mw-core-mock/core.event.v1.schema.json";
  static final String CUSTOMER_A = "01711000001";
  static final String CUSTOMER_B = "01711000002";
  static final String AGENT = "01811000001";
  static final String BROKE_MERCHANT = "01911000001";

  private final CoreCommandService service;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  CoreCommandServiceIT(
      CoreCommandService service,
      WalletSeeder seeder,
      JdbcTemplate jdbc,
      JsonMapper json) {
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

  // --- helpers ------------------------------------------------------------------------------

  /** A P2P from {@code from}, authorised by mw-auth's assertion of {@code authoriser} (normally the payer). */
  private CoreCommand command(String type, String commandId, String from, String to, long minor, String authoriser) {
    String txnId = uuidV7Shaped();
    java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
    p.put("txnType", "P2P");
    p.put("debitParty", new Party("WALLET", from, null, "CUSTOMER"));
    p.put("creditParty", new Party("WALLET", to, null, "CUSTOMER"));
    p.put("amount", Amount.bdt(minor));
    JsonNode payload = json.valueToTree(CoreCommandsTestSupport.authorised(p, authoriser));
    Instant now = Instant.now();
    return new CoreCommand(1, type, commandId, txnId, "corr-" + txnId, now, now.plusSeconds(300), payload);
  }

  /** A random UUID with the version nibble set to 7: the shape the TxnId contract requires. */
  private static String uuidV7Shaped() {
    String u = UUID.randomUUID().toString();
    return u.substring(0, 14) + "7" + u.substring(15);
  }

  private CoreCommand post(String from, String to, long minor) {
    return postAuthorisedBy(from, to, minor, from);
  }

  private CoreCommand postAuthorisedBy(String from, String to, long minor, String authoriser) {
    CoreCommand c = command("PostTransfer", "x", from, to, minor, authoriser);
    return new CoreCommand(1, c.commandType(), c.txnId() + ":post", c.txnId(), c.correlationId(), c.issuedAt(), c.expiresAt(), c.payload());
  }

  private CoreCommand validate(String from, String to, long minor, String authoriser) {
    CoreCommand c = command("ValidateTransaction", "x", from, to, minor, authoriser);
    return new CoreCommand(1, c.commandType(), c.txnId() + ":validate", c.txnId(), c.correlationId(), c.issuedAt(), c.expiresAt(), c.payload());
  }

  private CoreEvent handle(CoreCommand c) {
    CoreEvent e = service.handle(c).orElseThrow();
    assertThat(ContractSchemas.schema(EVENT_SCHEMA).validate(json.valueToTree(e)))
        .as("event %s against core.event.v1", e.eventType())
        .isEmpty();
    assertThat(e.commandId()).isEqualTo(c.commandId());
    assertThat(e.txnId()).isEqualTo(c.txnId());
    return e;
  }

  private long balance(String id) {
    return jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, id);
  }

  private long totalBalance() {
    return jdbc.queryForObject("select sum(balance_minor) from wallet", Long.class);
  }

  // --- ValidateTransaction ------------------------------------------------------------------

  @Test
  void validateSucceedsWithFeeAndLimitsAndMovesNoMoney() {
    CoreEvent e = handle(validate(CUSTOMER_A, CUSTOMER_B, 100_000, CUSTOMER_A));

    assertThat(e.eventType()).isEqualTo("TransactionValidated");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.SUCCESS);
    assertThat(json.valueToTree(e.payload()).path("fee").path("valueMinor").asLong()).isEqualTo(500);
    assertThat(json.valueToTree(e.payload()).path("limitsLeft").has("DAILY")).isTrue();
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000);
    assertThat(jdbc.queryForObject("select count(*) from entry", Long.class)).isZero();
  }

  @Test
  void validateAuthorisedByAnotherWalletIsRejected() {
    CoreEvent e = handle(validate(CUSTOMER_A, CUSTOMER_B, 100_000, CUSTOMER_B));

    assertThat(e.eventType()).isEqualTo("TransactionRejected");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.FAILURE);
    assertThat(e.reasonCode()).isEqualTo("MW-AUTH-046");
  }

  // --- PostTransfer -------------------------------------------------------------------------

  @Test
  void postTransferMovesAmountAndFeeInDoubleEntry() {
    CoreEvent e = handle(post(CUSTOMER_A, CUSTOMER_B, 100_000));

    assertThat(e.eventType()).isEqualTo("TransferPosted");
    assertThat(e.coreRef()).startsWith("CORE-");
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000 - 100_000 - 500);
    assertThat(balance(CUSTOMER_B)).isEqualTo(1_000_000 + 100_000);
    assertThat(balance(WalletSeeder.FEE_WALLET)).isEqualTo(500);
    assertThat(e.balancesAfter())
        .extracting(CoreEvent.BalanceAfter::walletId)
        .containsExactlyInAnyOrder(CUSTOMER_A, CUSTOMER_B);
    assertThat(jdbc.queryForObject("select sum(amount_minor) from entry where direction = 'DEBIT'", Long.class))
        .isEqualTo(jdbc.queryForObject("select sum(amount_minor) from entry where direction = 'CREDIT'", Long.class))
        .isEqualTo(100_500L);
  }

  @Test
  void anAssertionOfAnotherWalletIsRejectedAndNothingMoves() {
    CoreEvent e = handle(postAuthorisedBy(CUSTOMER_A, CUSTOMER_B, 100_000, CUSTOMER_B));

    assertThat(e.eventType()).isEqualTo("TransferRejected");
    assertThat(e.reasonCode()).isEqualTo("MW-AUTH-046");
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000);
    assertThat(jdbc.queryForObject("select count(*) from entry", Long.class)).isZero();
  }

  @Test
  void insufficientFundsIsRejected() {
    CoreEvent e = handle(post(BROKE_MERCHANT, CUSTOMER_B, 100));

    assertThat(e.eventType()).isEqualTo("TransferRejected");
    assertThat(e.reasonCode()).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(balance(CUSTOMER_B)).isEqualTo(1_000_000);
  }

  @Test
  void amountPlusFeeMustBeCoveredByTheBalance() {
    // balance 1,000,000; 499,500 + 500 fee is fine, but two of them leave too little for the fee
    handle(post(CUSTOMER_A, CUSTOMER_B, 499_500));
    assertThat(handle(post(CUSTOMER_A, CUSTOMER_B, 499_501)).reasonCode())
        .isEqualTo("INSUFFICIENT_BALANCE");
  }

  @Test
  void overThePerTransactionLimitIsRejected() {
    CoreEvent e = handle(post(AGENT, CUSTOMER_B, 500_001));

    assertThat(e.eventType()).isEqualTo("TransferRejected");
    assertThat(e.reasonCode()).isEqualTo("LIMIT_EXCEEDED_PER_TXN");
  }

  @Test
  void overTheDailyLimitIsRejected() {
    for (int i = 0; i < 3; i++) {
      assertThat(handle(post(AGENT, CUSTOMER_B, 500_000)).eventType()).isEqualTo("TransferPosted");
    }

    CoreEvent e = handle(post(AGENT, CUSTOMER_B, 100));
    assertThat(e.reasonCode()).isEqualTo("LIMIT_EXCEEDED_DAILY");
  }

  @Test
  void blockedAndUnknownWalletsAndOddRequestsAreRejected() {
    jdbc.update("update wallet set status = 'BLOCKED' where id = ?", CUSTOMER_A);
    assertThat(handle(post(CUSTOMER_A, CUSTOMER_B, 100)).reasonCode()).isEqualTo("ACCOUNT_BLOCKED");
    assertThat(handle(post(CUSTOMER_B, CUSTOMER_A, 100)).reasonCode()).isEqualTo("CREDIT_ACCOUNT_BLOCKED");
    assertThat(handle(post("01700000000", CUSTOMER_B, 100)).reasonCode()).isEqualTo("WALLET_NOT_FOUND");
    assertThat(handle(post(CUSTOMER_B, "01700000000", 100)).reasonCode())
        .isEqualTo("CREDIT_WALLET_NOT_FOUND");
    assertThat(handle(post(CUSTOMER_B, CUSTOMER_B, 100)).reasonCode()).isEqualTo("SAME_WALLET");
    assertThat(handle(post(CUSTOMER_B, AGENT, 0)).reasonCode()).isEqualTo("INVALID_AMOUNT");
  }

  // --- idempotency, invariant, odd commands -------------------------------------------------

  @Test
  void theSameCommandIdTwiceGivesOneLedgerEffectAndTwoIdenticalResultEvents() {
    CoreCommand c = post(CUSTOMER_A, CUSTOMER_B, 100_000);

    CoreEvent first = handle(c);
    CoreEvent second = handle(c);

    assertThat(second).isEqualTo(first);
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000 - 100_500);
    assertThat(jdbc.queryForObject("select count(*) from entry", Long.class)).isEqualTo(4L);
    List<String> published =
        jdbc.queryForList("select payload::text from outbox where topic = 'core.wallet.event.v1' order by id", String.class);
    assertThat(published).hasSize(2);
    assertThat(json.readTree(published.get(0))).isEqualTo(json.readTree(published.get(1)));
  }

  @Test
  void aRepeatedFailureAlsoReturnsTheSameFailureEvenIfTheWorldChanged() {
    CoreCommand c = post(BROKE_MERCHANT, CUSTOMER_B, 100);
    CoreEvent first = handle(c);
    jdbc.update("update wallet set balance_minor = 10000 where id = ?", BROKE_MERCHANT);

    assertThat(handle(c)).isEqualTo(first);
    assertThat(balance(BROKE_MERCHANT)).isEqualTo(10_000);
  }

  @Test
  void eventsAreStoredInTheOutboxKeyedByTxnIdWithTheCommandsCorrelation() {
    CoreCommand c = post(CUSTOMER_A, CUSTOMER_B, 100);
    handle(c);

    assertThat(jdbc.queryForObject("select msg_key from outbox", String.class)).isEqualTo(c.txnId());
    assertThat(jdbc.queryForObject("select headers->>'mw-correlation-id' from outbox", String.class))
        .isEqualTo(c.correlationId());
  }

  @Test
  void sumOfAllBalancesIsUnchangedByAnyNumberOfTransfers() {
    long before = totalBalance();
    Random random = new Random(42);
    List<String> wallets = List.of(CUSTOMER_A, CUSTOMER_B, AGENT, BROKE_MERCHANT, "01711000003", "01311000001");
    int posted = 0;
    for (int i = 0; i < 150; i++) {
      String from = wallets.get(random.nextInt(wallets.size()));
      String to = wallets.get(random.nextInt(wallets.size()));
      long amount = 1 + random.nextInt(600_000);
      String authoriser = random.nextInt(10) == 0 ? "01799999990" : from; // 1 in 10 not the payer's
      if ("TransferPosted".equals(handle(postAuthorisedBy(from, to, amount, authoriser)).eventType())) {
        posted++;
      }
    }

    assertThat(posted).isGreaterThan(10);
    assertThat(totalBalance()).isEqualTo(before);
    assertThat(jdbc.queryForObject("select sum(amount_minor) from entry where direction = 'DEBIT'", Long.class))
        .isEqualTo(jdbc.queryForObject("select sum(amount_minor) from entry where direction = 'CREDIT'", Long.class));
  }

  @Test
  void expiredCommandsAreDroppedWithoutAnEvent() {
    CoreCommand c = post(CUSTOMER_A, CUSTOMER_B, 100);
    CoreCommand stale =
        new CoreCommand(1, c.commandType(), c.commandId(), c.txnId(), c.correlationId(),
            Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), c.payload());

    assertThat(service.handle(stale)).isEmpty();
    assertThat(balance(CUSTOMER_A)).isEqualTo(1_000_000);
    assertThat(jdbc.queryForObject("select count(*) from outbox", Long.class)).isZero();
  }

  @Test
  void commandTypesTheCoreMockDoesNotKnowAreIgnored() {
    CoreCommand c = command("ApplyComplianceAction", "h-1", CUSTOMER_A, CUSTOMER_B, 100, CUSTOMER_A);

    assertThat(service.handle(c)).isEmpty();
  }
}
