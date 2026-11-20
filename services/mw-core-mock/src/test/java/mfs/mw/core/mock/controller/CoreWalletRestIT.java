package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.contracts.OpenApiSchemas;
import mfs.mw.core.mock.dto.Fault.FaultMode;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.service.mockcontrol.LegacyFaults;
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
  private final LegacyFaults faults;

  @Autowired
  CoreWalletRestIT(MockMvcTester mvc, WalletSeeder seeder, JdbcTemplate jdbc, JsonMapper json, LegacyFaults faults) {
    this.mvc = mvc;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
    this.faults = faults;
  }

  @BeforeEach
  void seed() {
    seeder.reset();
    faults.reset();
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

  /** An ops read (mw-admin-api): a balance without the customer's assertion. */
  private MvcTestResult adminGet(String uri) {
    return mvc.get().uri(uri).header("Authorization", InternalTokens.bearer("mw-admin-api")).exchange();
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
    return served(adminGet("/core/v1/balance/" + walletId), "/core/v1/balance/{walletId}", "get", 200)
        .path("available").path("valueMinor").asLong();
  }

  private long total(String walletId) {
    return served(adminGet("/core/v1/balance/" + walletId), "/core/v1/balance/{walletId}", "get", 200)
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
  void partialReversalsRefundUpToTheOriginalAndNoFurther() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed p2p = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 10000);
    served(post("/core/v1/transfers", p2p.body(), p2p.assertion()), "/core/v1/transfers", "post", 200);
    long payer = total(PAYER);
    long payee = total(PAYEE);

    String first = partial(CoreCommandsTestSupport.txnId(), txn, 4000);
    JsonNode refunded = served(post("/core/v1/reversals", first, null), "/core/v1/reversals", "post", 200);
    assertThat(refunded.path("status").asString()).isEqualTo("REVERSED");
    assertThat(served(post("/core/v1/reversals", first, null), "/core/v1/reversals", "post", 200))
        .as("the same refund again moves nothing").isEqualTo(refunded);
    served(post("/core/v1/reversals", partial(CoreCommandsTestSupport.txnId(), txn, 5000), null),
        "/core/v1/reversals", "post", 200);
    assertThat(total(PAYER)).as("money back to the original wallet, fee kept").isEqualTo(payer + 9000);
    assertThat(total(PAYEE)).isEqualTo(payee - 9000);

    JsonNode over = served(post("/core/v1/reversals", partial(CoreCommandsTestSupport.txnId(), txn, 2000), null),
        "/core/v1/reversals", "post", 422);
    assertThat(over.path("code").asString()).isEqualTo("MW-CORE-REFUND_EXCEEDS");
    String full = "{\"txnId\":\"" + CoreCommandsTestSupport.txnId() + "\",\"originalTxnId\":\"" + txn + "\"}";
    assertThat(served(post("/core/v1/reversals", full, null), "/core/v1/reversals", "post", 422).path("code").asString())
        .as("no full reversal after a partial one").isEqualTo("MW-CORE-REFUND_EXCEEDS");

    served(post("/core/v1/reversals", partial(CoreCommandsTestSupport.txnId(), txn, 1000), null),
        "/core/v1/reversals", "post", 200);
    assertThat(total(PAYER)).isEqualTo(payer + 10000);
    assertThat(served(post("/core/v1/reversals", partial(CoreCommandsTestSupport.txnId(), txn, 1), null),
        "/core/v1/reversals", "post", 422).path("code").asString()).isEqualTo("MW-CORE-ALREADY_REVERSED");
  }

  /** Security review of 116: refunds of one original racing each other never exceed it. */
  @Test
  void concurrentPartialReversalsNeverRefundMoreThanTheOriginal() throws Exception {
    for (int round = 0; round < 3; round++) {
      String txn = CoreCommandsTestSupport.txnId();
      Signed p2p = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 10000);
      served(post("/core/v1/transfers", p2p.body(), p2p.assertion()), "/core/v1/transfers", "post", 200);
      long payer = total(PAYER);

      int threads = 6;
      java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      java.util.List<java.util.concurrent.Future<MvcTestResult>> calls = new java.util.ArrayList<>();
      try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
        for (int i = 0; i < threads; i++) {
          String body = partial(CoreCommandsTestSupport.txnId(), txn, 4000);
          calls.add(pool.submit(() -> {
            start.await();
            return post("/core/v1/reversals", body, null);
          }));
        }
        start.countDown();
        int ok = 0;
        for (var call : calls) {
          MvcTestResult r = call.get();
          if (r.getResponse().getStatus() == 200) {
            ok++;
          } else {
            assertThat(r).hasStatus(422);
            assertThat(json.readTree(r.getResponse().getContentAsByteArray()).path("code").asString())
                .isEqualTo("MW-CORE-REFUND_EXCEEDS");
          }
        }
        assertThat(ok).as("two refunds of 40 Tk fit in 100 Tk, a third does not").isEqualTo(2);
      }
      assertThat(total(PAYER)).isEqualTo(payer + 8000);
    }
  }

  private static String partial(String txnId, String original, long minor) {
    return "{\"txnId\":\"" + txnId + "\",\"originalTxnId\":\"" + original + "\",\"amount\":{\"valueMinor\":" + minor
        + ",\"currency\":\"BDT\"},\"reason\":\"refund\"}";
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

  // --- validate and the customer's balance (ticket 95a) ---

  @Test
  void validateAnswersTheFeeMovesNothingAndTheTransferThenUsesTheSameAssertion() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed p2p = signed(txn, "P2P", PAYER, "WALLET", PAYEE, 10000);

    JsonNode validated = served(post("/core/v1/transactions/validate", p2p.body(), p2p.assertion()),
        "/core/v1/transactions/validate", "post", 200);
    JsonNode again = served(post("/core/v1/transactions/validate", p2p.body(), p2p.assertion()),
        "/core/v1/transactions/validate", "post", 200);

    assertThat(validated.path("status").asString()).isEqualTo("VALIDATED");
    assertThat(validated.path("txnId").asString()).isEqualTo(txn);
    assertThat(validated.path("fee").path("valueMinor").asLong()).isEqualTo(500);
    assertThat(validated.path("vat").path("valueMinor").asLong()).isEqualTo(65);
    assertThat(again).as("idempotent under <txnId>:rest:validate").isEqualTo(validated);
    assertThat(total(PAYER)).isEqualTo(SEED);
    assertThat(status(txn).path("status").asString()).isEqualTo("VALIDATED");
    assertThat(served(post("/core/v1/transfers", p2p.body(), p2p.assertion()), "/core/v1/transfers", "post", 200)
        .path("status").asString()).as("the jti belongs to this txn").isEqualTo("POSTED");
  }

  @Test
  void validateRefusesWithTheCoreReasonAndWithoutTheAssertion() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed toNobody = signed(txn, "P2P", PAYER, "WALLET", "01799999999", 10000);
    assertThat(served(post("/core/v1/transactions/validate", toNobody.body(), toNobody.assertion()),
        "/core/v1/transactions/validate", "post", 422).path("code").asString()).isEqualTo("MW-CORE-CREDIT_WALLET_NOT_FOUND");

    Signed unsigned = signed(CoreCommandsTestSupport.txnId(), "P2P", PAYER, "WALLET", PAYEE, 10000);
    assertThat(served(post("/core/v1/transactions/validate", unsigned.body(), null),
        "/core/v1/transactions/validate", "post", 401).path("code").asString()).isEqualTo("MW-AUTH-046");
  }

  @Test
  void aCustomerBalanceNeedsTheAssertionOfItsTxnAndItsJtiOnce() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed balance = signed(txn, "BALANCE", PAYER, "WALLET", PAYER, 0);
    String key = json.readTree(balance.body()).path("idempotencyKey").asString();

    JsonNode answer = served(balanceRead(PAYER, txn, balance.assertion(), key), "/core/v1/balance/{walletId}", "get", 200);
    assertThat(answer.path("available").path("valueMinor").asLong()).isEqualTo(SEED);
    assertThat(answer.path("asOf").asString()).isNotBlank();

    JsonNode replayed = served(balanceRead(PAYER, CoreCommandsTestSupport.txnId(), balance.assertion(), key),
        "/core/v1/balance/{walletId}", "get", 401);
    assertThat(replayed.path("code").asString()).as("the jti is the first txn's").isEqualTo("MW-AUTH-046");
    assertThat(served(get("/core/v1/balance/" + PAYER), "/core/v1/balance/{walletId}", "get", 401).path("code").asString())
        .as("the orchestrator reads a customer's balance for the customer only").isEqualTo("MW-AUTH-046");
    assertThat(served(get("/core/v1/balance/" + WalletSeeder.FEE_INCOME), "/core/v1/balance/{walletId}", "get", 200)
        .path("walletId").asString()).as("a system wallet needs none").isEqualTo(WalletSeeder.FEE_INCOME);
  }

  @Test
  void aRepeatedBalanceReadIsTheFirstAnswerAndAnotherAssertionUnderItsKeyIsAConflict() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed balance = signed(txn, "BALANCE", PAYER, "WALLET", PAYER, 0);
    String key = json.readTree(balance.body()).path("idempotencyKey").asString();
    JsonNode first = served(balanceRead(PAYER, txn, balance.assertion(), key), "/core/v1/balance/{walletId}", "get", 200);
    Signed hold = signed(CoreCommandsTestSupport.txnId(), "TOPUP", PAYER, "MSISDN", PAYER, 5000);
    served(post("/core/v1/holds", hold.body(), hold.assertion()), "/core/v1/holds", "post", 200);

    JsonNode again = served(balanceRead(PAYER, txn, balance.assertion(), key), "/core/v1/balance/{walletId}", "get", 200);
    assertThat(again).as("answered from the stored inquiry, no live wallet fields").isEqualTo(first);
    assertThat(again.path("held").path("valueMinor").asLong()).isZero();
    assertThat(again.path("total").path("valueMinor").asLong()).isEqualTo(SEED);

    String other = TestAssertions.mint(TestAssertions.claims(PAYER, "BALANCE", new TxnHash.Party("WALLET", PAYER),
        new TxnHash.Party("WALLET", PAYER), 0, key).build());
    assertThat(served(balanceRead(PAYER, txn, other, key), "/core/v1/balance/{walletId}", "get", 409).path("code").asString())
        .as("another assertion under the same txn and key").isEqualTo("MW-IDEM-001");
  }

  @Test
  void aBalanceInquiryRefusalIsTheCoreReason() {
    String txn = CoreCommandsTestSupport.txnId();
    faults.add(new FaultRequest(FaultMode.REJECT, "BalanceInquiry", txn, null, "WALLET_NOT_ACTIVE", 1));
    Signed balance = signed(txn, "BALANCE", PAYER, "WALLET", PAYER, 0);
    String key = json.readTree(balance.body()).path("idempotencyKey").asString();

    assertThat(served(balanceRead(PAYER, txn, balance.assertion(), key), "/core/v1/balance/{walletId}", "get", 422)
        .path("code").asString()).isEqualTo("MW-CORE-WALLET_NOT_ACTIVE");
  }

  private MvcTestResult balanceRead(String walletId, String txnId, String assertion, String key) {
    return mvc.get().uri("/core/v1/balance/" + walletId + "?txnId=" + txnId)
        .header("Authorization", InternalTokens.bearer("mw-orchestrator"))
        .header("X-Txn-Assertion", assertion).header("Idempotency-Key", key).exchange();
  }

  // --- operator rounds (ticket 95a) ---

  @Test
  void anOperatorRoundIsDecidedAfreshWhileTheSagasOwnCommitRepeatsItsRefusal() {
    String txn = CoreCommandsTestSupport.txnId();
    Signed hold = signed(txn, "TOPUP", PAYER, "MSISDN", PAYER, 5000);
    String holdId = served(post("/core/v1/holds", hold.body(), hold.assertion()), "/core/v1/holds", "post", 200)
        .path("holdId").asString();
    faults.add(new FaultRequest(FaultMode.REJECT, "CommitHold", txn, null, "INJECTED_REJECT", 1));

    for (int i = 0; i < 2; i++) {
      assertThat(served(post("/core/v1/holds/" + holdId + "/commit", null, null), "/core/v1/holds/{holdId}/commit", "post", 422)
          .path("code").asString()).isEqualTo("MW-CORE-INJECTED_REJECT");
    }
    JsonNode forced = served(post("/core/v1/holds/" + holdId + "/commit", "{\"round\":1}", null),
        "/core/v1/holds/{holdId}/commit", "post", 200);

    assertThat(forced.path("status").asString()).isEqualTo("COMMITTED");
    assertThat(total(PAYER)).isEqualTo(SEED - 5200);
  }

  // --- e-money total (ticket 127c, REC-07: mw-recon's trust position job) ---

  private MvcTestResult getAs(String client, String uri) {
    return mvc.get().uri(uri).header("Authorization", InternalTokens.bearer(client)).exchange();
  }

  @Test
  void theEmoneyTotalSumsEveryHolderWalletWithHeldMoneyAndLeavesSystemWalletsOut() {
    jdbc.update("update wallet set balance_minor = 0, held_minor = 0");
    jdbc.update("update wallet set balance_minor = 100000, held_minor = 30000 where id = ?", PAYER);
    jdbc.update("update wallet set balance_minor = 25000, status = 'LOCKED' where id = ?", PAYEE);
    jdbc.update("insert into wallet (id, msisdn, type, balance_minor) values ('T-AGENT-127C', null, 'AGENT', 70000)");
    jdbc.update("insert into wallet (id, msisdn, type, balance_minor) values ('T-DSO-127C', null, 'DSO', 5)");
    jdbc.update("insert into wallet (id, msisdn, type, balance_minor) values ('T-SYS-127C', null, 'SYSTEM', 999999)");
    try {
      JsonNode total = served(getAs("mw-recon", "/core/v1/reports/emoney-total"), "/core/v1/reports/emoney-total", "get", 200);

      assertThat(total.path("total").path("valueMinor").asLong()).isEqualTo(195005);
      assertThat(total.path("asOf").asString()).isNotBlank();
      java.util.Map<String, Long> byType = new java.util.HashMap<>();
      total.path("byType").forEach(t -> byType.put(t.path("type").asString(), t.path("total").path("valueMinor").asLong()));
      assertThat(byType).containsEntry("CUSTOMER", 125000L).containsEntry("AGENT", 70000L).containsEntry("DSO", 5L)
          .doesNotContainKey("SYSTEM");
    } finally {
      jdbc.update("delete from wallet where id like 'T-%-127C'");
    }
  }

  @Test
  void onlyMwReconMayReadTheEmoneyTotal() {
    assertThat(getAs("mw-orchestrator", "/core/v1/reports/emoney-total")).hasStatus(403);
    assertThat(getAs("mw-admin-api", "/core/v1/reports/emoney-total")).hasStatus(403);
    assertThat(mvc.get().uri("/core/v1/reports/emoney-total").exchange()).hasStatus(401);
  }

  @Test
  void aMwReconTokenReadsTheReportAndNothingElse() {
    long before = total(PAYER);
    Signed s = signed(CoreCommandsTestSupport.txnId(), "P2P", PAYER, "WALLET", PAYEE, 1000);
    assertThat(mvc.post().uri("/core/v1/transfers").header("Authorization", InternalTokens.bearer("mw-recon"))
        .header("X-Txn-Assertion", s.assertion()).contentType(MediaType.APPLICATION_JSON).content(s.body()).exchange())
        .hasStatus(403);
    assertThat(postAs("mw-recon", "/core/v1/credits", "{}")).hasStatus(403);
    assertThat(getAs("mw-recon", "/core/v1/balance/" + PAYER)).hasStatus(403);
    assertThat(getAs("mw-recon", "/core/v1/accounts/" + PAYER)).hasStatus(403);
    assertThat(total(PAYER)).isEqualTo(before);
    assertThat(getAs("mw-recon", "/core/v1/reports/emoney-total")).hasStatus(200);
  }

  // --- org wallet balance (ticket 129c: mw-portal-api's bulk balance check) ---

  private static final String ORG_WALLET = "01311000001";

  @Test
  void mwPortalApiReadsAnOrgWalletsBalanceAndNothingElse() {
    long before = total(PAYER);
    JsonNode org =
        served(getAs("mw-portal-api", "/core/v1/balance/" + ORG_WALLET), "/core/v1/balance/{walletId}", "get", 200);
    assertThat(org.path("walletId").asString()).isEqualTo(ORG_WALLET);
    assertThat(org.path("available").path("valueMinor").asLong()).isEqualTo(available(ORG_WALLET));

    // a customer's balance needs the customer's own authorisation
    assertThat(getAs("mw-portal-api", "/core/v1/balance/" + PAYER)).hasStatus(401);
    Signed s = signed(CoreCommandsTestSupport.txnId(), "P2P", PAYER, "WALLET", PAYEE, 1000);
    assertThat(mvc.post().uri("/core/v1/transfers").header("Authorization", InternalTokens.bearer("mw-portal-api"))
        .header("X-Txn-Assertion", s.assertion()).contentType(MediaType.APPLICATION_JSON).content(s.body()).exchange())
        .hasStatus(403);
    assertThat(getAs("mw-portal-api", "/core/v1/accounts/" + ORG_WALLET)).hasStatus(403);
    assertThat(getAs("mw-portal-api", "/core/v1/reports/emoney-total")).hasStatus(403);
    assertThat(total(PAYER)).isEqualTo(before);
  }

  // --- account unlock and KYC factors (ticket 95b: mw-admin-api and mw-auth over REST) ---

  private MvcTestResult postAs(String client, String uri, String body) {
    return mvc.post().uri(uri).header("Authorization", InternalTokens.bearer(client))
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private String walletStatus(String walletId) {
    return jdbc.queryForObject("select status from wallet where id = ?", String.class, walletId);
  }

  private static String unlockBody(String txnId) {
    return """
        {"txnId":"%s","changeRequestId":"%s","makerId":"ops-maker","checkerId":"ops-checker"}"""
        .formatted(txnId, UUID.randomUUID());
  }

  private static String factorsBody(String txnId) {
    return """
        {"txnId":"%s","resetId":"%s","factors":{"nidLast4Token":"NID_AAAA","dateOfBirthToken":"NID_BBBB","tokenKid":"tokenise-nid.v1"}}"""
        .formatted(txnId, UUID.randomUUID());
  }

  @Test
  void anOpsUnlockLiftsACoreLockOnceAndARepeatIsTheSameAnswer() {
    jdbc.update("update wallet set status = 'LOCKED' where id = ?", PAYER);
    String body = unlockBody(CoreCommandsTestSupport.txnId());

    JsonNode first = served(postAs("mw-admin-api", "/core/v1/accounts/" + PAYER + "/unlock", body),
        "/core/v1/accounts/{walletId}/unlock", "post", 200);
    assertThat(first.path("status").asString()).isEqualTo("UNLOCKED");
    assertThat(first.path("walletId").asString()).isEqualTo(PAYER);
    assertThat(walletStatus(PAYER)).isEqualTo("ACTIVE");

    jdbc.update("update wallet set status = 'LOCKED' where id = ?", PAYER);
    JsonNode again = served(postAs("mw-admin-api", "/core/v1/accounts/" + PAYER + "/unlock", body),
        "/core/v1/accounts/{walletId}/unlock", "post", 200);
    assertThat(again).isEqualTo(first);
    assertThat(walletStatus(PAYER)).as("a repeat unlocks nothing").isEqualTo("LOCKED");
  }

  @Test
  void anUnlockOfAWalletThatIsNotLockedOrUnknownIsTheCoreRefusal() {
    assertThat(served(postAs("mw-admin-api", "/core/v1/accounts/" + PAYER + "/unlock", unlockBody(CoreCommandsTestSupport.txnId())),
        "/core/v1/accounts/{walletId}/unlock", "post", 422).path("code").asString()).isEqualTo("MW-CORE-WALLET_NOT_LOCKED");
    assertThat(served(postAs("mw-admin-api", "/core/v1/accounts/01799999999/unlock", unlockBody(CoreCommandsTestSupport.txnId())),
        "/core/v1/accounts/{walletId}/unlock", "post", 422).path("code").asString()).isEqualTo("MW-CORE-WALLET_NOT_FOUND");
  }

  @Test
  void onlyMwAdminApiMayUnlockAndOnlyMwAuthMayVerifyFactors() {
    jdbc.update("update wallet set status = 'LOCKED' where id = ?", PAYER);
    assertThat(postAs("mw-orchestrator", "/core/v1/accounts/" + PAYER + "/unlock", unlockBody(CoreCommandsTestSupport.txnId())))
        .hasStatus(403);
    assertThat(postAs("mw-auth", "/core/v1/accounts/" + PAYER + "/unlock", unlockBody(CoreCommandsTestSupport.txnId())))
        .hasStatus(403);
    assertThat(walletStatus(PAYER)).isEqualTo("LOCKED");
    assertThat(postAs("mw-admin-api", "/core/v1/customers/" + PAYER + "/verify-factors", factorsBody(CoreCommandsTestSupport.txnId())))
        .hasStatus(403);
  }

  @Test
  void theFactorsOfAnActiveOrCoreLockedWalletAreVerified() {
    JsonNode ok = served(postAs("mw-auth", "/core/v1/customers/" + PAYER + "/verify-factors", factorsBody(CoreCommandsTestSupport.txnId())),
        "/core/v1/customers/{walletId}/verify-factors", "post", 200);
    assertThat(ok.path("status").asString()).isEqualTo("VERIFIED");
    assertThat(ok.path("walletId").asString()).isEqualTo(PAYER);

    jdbc.update("update wallet set status = 'LOCKED' where id = ?", PAYER);
    assertThat(served(postAs("mw-auth", "/core/v1/customers/" + PAYER + "/verify-factors", factorsBody(CoreCommandsTestSupport.txnId())),
        "/core/v1/customers/{walletId}/verify-factors", "post", 200).path("status").asString()).isEqualTo("VERIFIED");
  }

  @Test
  void theFactorsOfABlockedWalletOrAnInjectedMismatchAreRefused() {
    String txn = CoreCommandsTestSupport.txnId();
    faults.add(new FaultRequest(FaultMode.REJECT, "VerifyCustomerFactors", txn, null, "FACTORS_MISMATCH", 1));
    assertThat(served(postAs("mw-auth", "/core/v1/customers/" + PAYER + "/verify-factors", factorsBody(txn)),
        "/core/v1/customers/{walletId}/verify-factors", "post", 422).path("code").asString())
        .isEqualTo("MW-CORE-FACTORS_MISMATCH");

    jdbc.update("update wallet set status = 'BLOCKED' where id = ?", PAYEE);
    assertThat(served(postAs("mw-auth", "/core/v1/customers/" + PAYEE + "/verify-factors", factorsBody(CoreCommandsTestSupport.txnId())),
        "/core/v1/customers/{walletId}/verify-factors", "post", 422).path("code").asString())
        .isEqualTo("MW-CORE-WALLET_NOT_ACTIVE");
  }
}
