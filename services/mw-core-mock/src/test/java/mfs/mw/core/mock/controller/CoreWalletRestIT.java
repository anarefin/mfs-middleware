package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.contracts.OpenApiSchemas;
import mfs.mw.core.mock.security.InternalTokens;
import mfs.mw.core.mock.security.TestAssertions;
import mfs.mw.core.mock.service.CoreCommandsTestSupport;
import mfs.mw.core.mock.service.WalletSeeder;
import mfs.mw.core.mock.util.TxnHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet REST API end to end on the real ledger (FRS 7.2, 7.3, ADR D31, ticket 93): money
 * moves as the Kafka commands move it, every operation is idempotent by txnId, and every served body
 * validates against {@code core-wallet.v1.openapi.yaml}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class CoreWalletRestIT {

  static final String API = "api/mw-core-mock/core-wallet.v1.openapi.yaml";
  private static final String PAYER = "01711000001";
  private static final String PAYEE = "01711000002";
  private static final long SEED = 1_000_000;

  private final MockMvcTester mvc;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  CoreWalletRestIT(MockMvcTester mvc, WalletSeeder seeder, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void seed() {
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  // --- helpers ---

  /** A transfer-shaped body and the assertion mw-auth would sign for it. */
  private record Signed(String body, String assertion) {}

  private static Signed signed(String txnId, String txnType, String from, String toType, String to, long minor) {
    String key = UUID.randomUUID().toString();
    String assertion = TestAssertions.mint(TestAssertions.claims(from, txnType, new TxnHash.Party("WALLET", from),
        new TxnHash.Party(toType, to), minor, key).build());
    String body = """
        {"txnId":"%s","txnType":"%s","debitParty":{"type":"WALLET","id":"%s"},"creditParty":{"type":"%s","id":"%s"},
         "amount":{"valueMinor":%d,"currency":"BDT"},"idempotencyKey":"%s"}""".formatted(txnId, txnType, from, toType, to, minor, key);
    return new Signed(body, assertion);
  }

  private MvcTestResult post(String uri, String body, String assertion) {
    var req = mvc.post().uri(uri).header("Authorization", InternalTokens.bearer("mw-orchestrator"))
        .contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body);
    if (assertion != null) {
      req = req.header("X-Txn-Assertion", assertion);
    }
    return req.exchange();
  }

  private MvcTestResult get(String uri) {
    return mvc.get().uri(uri).header("Authorization", InternalTokens.bearer("mw-orchestrator")).exchange();
  }

  /** The body, after checking it against the contract's response schema of {@code path}/{@code method}. */
  private JsonNode served(MvcTestResult r, String path, String method, int status) {
    assertThat(r).hasStatus(status);
    JsonNode body = json.readTree(r.getResponse().getContentAsByteArray());
    // error responses are a $ref to components/responses/Error: their body is the ErrorEnvelope
    var schema = status >= 400 ? OpenApiSchemas.component(API, "ErrorEnvelope")
        : OpenApiSchemas.response(API, path, method, String.valueOf(status));
    assertThat(schema.validate(body))
        .as("%s %s %d against core-wallet.v1: %s", method, path, status, body).isEmpty();
    return body;
  }

  private long available(String walletId) {
    return served(get("/core/v1/balance/" + walletId), "/core/v1/balance/{walletId}", "get", 200)
        .path("available").path("valueMinor").asLong();
  }

  private long total(String walletId) {
    return served(get("/core/v1/balance/" + walletId), "/core/v1/balance/{walletId}", "get", 200)
        .path("total").path("valueMinor").asLong();
  }

  private JsonNode status(String txnId) {
    return served(get("/core/v1/transactions/" + txnId), "/core/v1/transactions/{txnId}", "get", 200);
  }

  private static long sum(JsonNode entries, String direction) {
    long s = 0;
    for (JsonNode e : entries) {
      if (direction.equals(e.path("direction").asString())) {
        s += e.path("amount").path("valueMinor").asLong();
      }
    }
    return s;
  }

  // --- WAL-06..08 ---

  @Test
  void holdThenCommitMovesTheMoneyWithZeroSumEntries() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed hold = signed(txn, "TOPUP", PAYER, "MSISDN", PAYER, 5000);

    JsonNode held = served(post("/core/v1/holds", hold.body(), hold.assertion()), "/core/v1/holds", "post", 200);
    assertThat(held.path("status").asString()).isEqualTo("HELD");
    String holdId = held.path("holdId").asString();
    assertThat(available(PAYER)).as("amount + TOPUP fee 200 reserved").isEqualTo(SEED - 5200);
    assertThat(total(PAYER)).isEqualTo(SEED);
    assertThat(status(txn).path("status").asString()).isEqualTo("HELD");

    JsonNode committed = served(post("/core/v1/holds/" + holdId + "/commit", null, null),
        "/core/v1/holds/{holdId}/commit", "post", 200);
    assertThat(committed.path("status").asString()).isEqualTo("COMMITTED");
    assertThat(committed.path("txnId").asString()).isEqualTo(txn);
    assertThat(total(PAYER)).isEqualTo(SEED - 5200);
    assertThat(available(PAYER)).isEqualTo(SEED - 5200);

    JsonNode after = status(txn);
    assertThat(after.path("status").asString()).isEqualTo("COMMITTED");
    assertThat(after.path("entries")).hasSize(5);
    assertThat(sum(after.path("entries"), "DEBIT")).as("debits equal credits (FRS 7.3)")
        .isEqualTo(sum(after.path("entries"), "CREDIT")).isEqualTo(5200);
  }

  @Test
  void holdThenReleaseRestoresTheAvailableBalance() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed hold = signed(txn, "TOPUP", PAYER, "MSISDN", PAYER, 5000);
    String holdId = served(post("/core/v1/holds", hold.body(), hold.assertion()), "/core/v1/holds", "post", 200)
        .path("holdId").asString();

    JsonNode released = served(post("/core/v1/holds/" + holdId + "/release", "{\"reason\":\"partner failed\"}", null),
        "/core/v1/holds/{holdId}/release", "post", 200);

    assertThat(released.path("status").asString()).isEqualTo("RELEASED");
    assertThat(available(PAYER)).isEqualTo(SEED);
    assertThat(status(txn).path("entries")).isEmpty();
    JsonNode commitAfter = served(post("/core/v1/holds/" + holdId + "/commit", null, null),
        "/core/v1/holds/{holdId}/commit", "post", 422);
    assertThat(commitAfter.path("code").asString()).isEqualTo("MW-CORE-HOLD_RELEASED");
  }

  @Test
  void anUnknownHoldIs422HoldNotFound() {
    assertThat(served(post("/core/v1/holds/HOLD-NOPE/commit", null, null), "/core/v1/holds/{holdId}/commit", "post", 422)
        .path("code").asString()).isEqualTo("MW-CORE-HOLD_NOT_FOUND");
  }

  // --- WAL-09 and idempotency (FRS 7.3) ---

  @Test
  void aRepeatWithTheSameTxnIdIsTheSameAnswerAndNoSecondPosting() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed p2p = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 10000);

    JsonNode first = served(post("/core/v1/transfers", p2p.body(), p2p.assertion()), "/core/v1/transfers", "post", 200);
    JsonNode again = served(post("/core/v1/transfers", p2p.body(), p2p.assertion()), "/core/v1/transfers", "post", 200);

    assertThat(first.path("status").asString()).isEqualTo("POSTED");
    assertThat(again).isEqualTo(first);
    assertThat(total(PAYER)).as("10000 + P2P fee 500, once").isEqualTo(SEED - 10500);
    assertThat(total(PAYEE)).isEqualTo(SEED + 10000);
    assertThat(status(txn).path("entries")).hasSize(5);

    Signed other = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 20000);
    JsonNode conflict = served(post("/core/v1/transfers", other.body(), other.assertion()), "/core/v1/transfers", "post", 409);
    assertThat(conflict.path("code").asString()).isEqualTo("MW-IDEM-001");
    assertThat(total(PAYER)).isEqualTo(SEED - 10500);
  }

  @Test
  void aRefusalIsTheCoreReasonAndItsRepeatTheSameRefusal() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed big = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 400000);
    jdbc.update("update wallet set balance_minor = 1000 where id = ?", PAYER);

    for (int i = 0; i < 2; i++) {
      JsonNode refused = served(post("/core/v1/transfers", big.body(), big.assertion()), "/core/v1/transfers", "post", 422);
      assertThat(refused.path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
      assertThat(refused.path("txnId").asString()).isEqualTo(txn);
    }
    assertThat(status(txn).path("status").asString()).isEqualTo("REJECTED");
  }

  @Test
  void anAssertionForAnotherAmountIs401Auth046() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed signedFor100 = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 100);
    String tampered = signedFor100.body().replace("\"valueMinor\":100,", "\"valueMinor\":90000,");

    JsonNode refused = served(post("/core/v1/transfers", tampered, signedFor100.assertion()), "/core/v1/transfers", "post", 401);

    assertThat(refused.path("code").asString()).isEqualTo("MW-AUTH-046");
    assertThat(total(PAYER)).isEqualTo(SEED);
  }

  // --- WAL-10, WAL-11 ---

  @Test
  void aReversalUndoesTheTransferOnceAndASecondIsAlreadyReversed() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed p2p = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 10000);
    served(post("/core/v1/transfers", p2p.body(), p2p.assertion()), "/core/v1/transfers", "post", 200);

    String reversal = CoreCommandsTestSupport.txnId();
    String body = "{\"txnId\":\"" + reversal + "\",\"originalTxnId\":\"" + txn + "\",\"reason\":\"dispute\"}";
    JsonNode reversed = served(post("/core/v1/reversals", body, null), "/core/v1/reversals", "post", 200);
    assertThat(reversed.path("status").asString()).isEqualTo("REVERSED");
    assertThat(reversed.path("originalTxnId").asString()).isEqualTo(txn);
    assertThat(total(PAYER)).as("amount and fee back").isEqualTo(SEED);
    assertThat(total(PAYEE)).isEqualTo(SEED);
    assertThat(served(post("/core/v1/reversals", body, null), "/core/v1/reversals", "post", 200))
        .as("the same reversal again").isEqualTo(reversed);

    String second = "{\"txnId\":\"" + CoreCommandsTestSupport.txnId() + "\",\"originalTxnId\":\"" + txn + "\"}";
    assertThat(served(post("/core/v1/reversals", second, null), "/core/v1/reversals", "post", 422).path("code").asString())
        .isEqualTo("MW-CORE-ALREADY_REVERSED");
    assertThat(status(txn).path("status").asString()).isEqualTo("REVERSED");
    assertThat(total(PAYER)).isEqualTo(SEED);
  }

  @Test
  void aCreditComesFromSettlementOnce() {
    String txn = CoreCommandsTestSupport.txnId();
    String body = "{\"txnId\":\"" + txn + "\",\"txnType\":\"ADD_MONEY_BANK\",\"creditParty\":{\"type\":\"WALLET\",\"id\":\"" + PAYEE
        + "\"},\"amount\":{\"valueMinor\":7000,\"currency\":\"BDT\"}}";
    long settlement = total(WalletSeeder.SETTLEMENT_WALLET);

    JsonNode credited = served(post("/core/v1/credits", body, null), "/core/v1/credits", "post", 200);
    served(post("/core/v1/credits", body, null), "/core/v1/credits", "post", 200);

    assertThat(credited.path("status").asString()).isEqualTo("CREDITED");
    assertThat(total(PAYEE)).isEqualTo(SEED + 7000);
    assertThat(total(WalletSeeder.SETTLEMENT_WALLET)).isEqualTo(settlement - 7000);
  }

  @Test
  void aCreditDrawsOnThePartnerSettlementWalletItNames() {
    String txn = CoreCommandsTestSupport.txnId();
    String body = "{\"txnId\":\"" + txn + "\",\"txnType\":\"ADD_MONEY_BANK\",\"creditParty\":{\"type\":\"WALLET\",\"id\":\"" + PAYEE
        + "\"},\"amount\":{\"valueMinor\":7000,\"currency\":\"BDT\"},\"settlementWallet\":\"SETTLEMENT_UCB_IMT\"}";
    long imt = total("SETTLEMENT_UCB_IMT");
    long settlement = total(WalletSeeder.SETTLEMENT_WALLET);

    served(post("/core/v1/credits", body, null), "/core/v1/credits", "post", 200);

    assertThat(total("SETTLEMENT_UCB_IMT")).isEqualTo(imt - 7000);
    assertThat(total(WalletSeeder.SETTLEMENT_WALLET)).isEqualTo(settlement);
    String unknown = body.replace(txn, CoreCommandsTestSupport.txnId()).replace("SETTLEMENT_UCB_IMT", "SETTLEMENT_NOWHERE");
    assertThat(served(post("/core/v1/credits", unknown, null), "/core/v1/credits", "post", 422).path("code").asString())
        .isEqualTo("MW-CORE-SETTLEMENT_WALLET_NOT_FOUND");
  }

  // --- WAL-01, 03, 04, 05, 12, 13 reads ---

  @Test
  void theReadsServeTheContractShapes() {
    JsonNode account = served(get("/core/v1/accounts/" + PAYER), "/core/v1/accounts/{msisdn}", "get", 200);
    assertThat(account.path("type").asString()).isEqualTo("CUSTOMER");
    assertThat(account.path("holderName").asString()).isEqualTo("Rahim Uddin");
    assertThat(served(get("/core/v1/accounts/01799999999"), "/core/v1/accounts/{msisdn}", "get", 404).path("code").asString())
        .isEqualTo("MW-VAL-003");

    JsonNode charges = served(post("/core/v1/charges/calculate", "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":10000,\"currency\":\"BDT\"}}", null),
        "/core/v1/charges/calculate", "post", 200);
    assertThat(charges.path("fee").path("valueMinor").asLong()).isEqualTo(500);
    assertThat(charges.path("vat").path("valueMinor").asLong()).as("15 %% inside the fee").isEqualTo(65);
    assertThat(charges.path("total").path("valueMinor").asLong()).isEqualTo(10500);
    JsonNode cashOut = served(post("/core/v1/charges/calculate",
        "{\"txnType\":\"CASH_OUT\",\"amount\":{\"valueMinor\":600000,\"currency\":\"BDT\"}}", null),
        "/core/v1/charges/calculate", "post", 200);
    // the second slab: 46.25 Tk, VAT 6.03 Tk, agent 40 % and distributor 10 % of the 40.22 Tk net
    assertThat(cashOut.path("fee").path("valueMinor").asLong()).isEqualTo(4625);
    assertThat(cashOut.path("vat").path("valueMinor").asLong()).isEqualTo(603);
    assertThat(cashOut.path("commissionSplit").path("AGENT").path("valueMinor").asLong()).isEqualTo(1608);
    assertThat(cashOut.path("commissionSplit").path("DISTRIBUTOR").path("valueMinor").asLong()).isEqualTo(402);
    assertThat(cashOut.path("commission").path("valueMinor").asLong()).isEqualTo(2010);

    JsonNode ok = served(post("/core/v1/limits/check",
        "{\"walletId\":\"" + PAYER + "\",\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":10000,\"currency\":\"BDT\"}}", null),
        "/core/v1/limits/check", "post", 200);
    assertThat(ok.path("allowed").asBoolean()).isTrue();
    assertThat(ok.path("remaining").path("daily").path("valueMinor").asLong()).isEqualTo(1_500_000);
    assertThat(ok.path("remaining").path("monthly").path("valueMinor").asLong()).isEqualTo(20_000_000);
    assertThat(ok.path("remaining").path("dailyCount").asLong()).isEqualTo(500);
    JsonNode basic = served(post("/core/v1/limits/check",
        "{\"walletId\":\"01711000950\",\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":150000,\"currency\":\"BDT\"}}", null),
        "/core/v1/limits/check", "post", 200);
    assertThat(basic.path("allowed").asBoolean()).as("a BASIC KYC customer pays at most 1,000 Tk").isFalse();
    assertThat(basic.path("reasonCode").asString()).isEqualTo("LIMIT_EXCEEDED_PER_TXN");
    JsonNode tooBig = served(post("/core/v1/limits/check",
        "{\"walletId\":\"" + PAYER + "\",\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":500001,\"currency\":\"BDT\"}}", null),
        "/core/v1/limits/check", "post", 200);
    assertThat(tooBig.path("allowed").asBoolean()).isFalse();
    assertThat(tooBig.path("reasonCode").asString()).isEqualTo("LIMIT_EXCEEDED_PER_TXN");

    assertThat(status(CoreCommandsTestSupport.txnId()).path("status").asString()).isEqualTo("NOT_FOUND");
  }

  @Test
  void aStatementListsTheWalletsEntriesInTheRange() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed p2p = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 10000);
    served(post("/core/v1/transfers", p2p.body(), p2p.assertion()), "/core/v1/transfers", "post", 200);

    JsonNode statement = served(get("/core/v1/statements?walletId=" + PAYER + "&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z"),
        "/core/v1/statements", "get", 200);

    assertThat(statement.path("entries")).hasSize(2);
    assertThat(List.of(statement.path("entries").get(0).path("kind").asString(), statement.path("entries").get(1).path("kind").asString()))
        .containsExactly("TRANSFER", "FEE");
    // the balance after each entry (ticket 94): 10,000 - 100 Tk, then the 5 Tk fee
    assertThat(statement.path("entries").get(0).path("balanceAfter").path("valueMinor").asLong()).isEqualTo(SEED - 10000);
    assertThat(statement.path("entries").get(1).path("balanceAfter").path("valueMinor").asLong()).isEqualTo(SEED - 10500);
    assertThat(statement.path("hasMore").asBoolean()).isFalse();
    JsonNode second = served(get("/core/v1/statements?walletId=" + PAYER
        + "&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z&limit=1&page=1"), "/core/v1/statements", "get", 200);
    assertThat(second.path("page").asInt()).isEqualTo(1);
    assertThat(second.path("hasMore").asBoolean()).isFalse();
    assertThat(second.path("entries")).hasSize(1);
    assertThat(second.path("entries").get(0).path("kind").asString()).isEqualTo("FEE");
    assertThat(served(get("/core/v1/statements?walletId=" + PAYER
        + "&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z&limit=1"), "/core/v1/statements", "get", 200)
        .path("hasMore").asBoolean()).isTrue();
    assertThat(served(get("/core/v1/statements?walletId=" + PAYER + "&from=2000-01-01T00:00:00Z&to=2000-01-02T00:00:00Z"),
        "/core/v1/statements", "get", 200).path("entries")).isEmpty();
  }
}
