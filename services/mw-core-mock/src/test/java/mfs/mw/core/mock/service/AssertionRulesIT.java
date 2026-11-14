package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.Party;
import mfs.mw.core.mock.security.PinKeyProvider;
import mfs.mw.core.mock.security.TestAssertions;
import mfs.mw.core.mock.util.TxnHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth's transaction assertion at the core (spec 18.7, D22; ticket 71), against a real
 * PostgreSQL ledger: ValidateTransaction, PostTransfer and HoldFunds carrying an {@code assertion}
 * instead of a PIN are authorised by its ES256 signature (key set fetched from {@link
 * TestAssertions#jwksUri()}), {@code aud} core, {@code exp} with 30 s skew, the {@code txnHash}
 * recomputed from the command, {@code sub} = the authorising wallet and a {@code jti} used by no
 * other transaction ({@code coremock.used_assertion}). Every refusal is the command's failure event
 * with reason {@code MW-AUTH-046} and moves nothing; no PIN is checked and no lockout counted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class AssertionRulesIT {

  static final String A = "01711000001";
  static final String B = "01711000002";
  static final TxnHash.Party PAYER = new TxnHash.Party("WALLET", A);
  static final TxnHash.Party PAYEE = new TxnHash.Party("WALLET", B);

  private final CoreCommandService service;
  private final WalletSeeder seeder;
  private final UsedAssertionPurge purge;
  private final JdbcTemplate jdbc;
  private final CoreCommandsTestSupport cmds;

  @Autowired
  AssertionRulesIT(CoreCommandService service, WalletSeeder seeder, UsedAssertionPurge purge, JdbcTemplate jdbc,
      PinKeyProvider keys, JsonMapper json) {
    this.service = service;
    this.seeder = seeder;
    this.purge = purge;
    this.jdbc = jdbc;
    this.cmds = new CoreCommandsTestSupport(keys, json);
  }

  @BeforeEach
  void freshLedger() {
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  long balance(String id) {
    return jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, id);
  }

  Map<String, Object> transfer(String txnType, String to, String toType, long minor, String assertion, String key) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("txnType", txnType);
    p.put("debitParty", new Party("WALLET", A, null, "CUSTOMER"));
    p.put("creditParty", new Party(toType, to, null, "WALLET".equals(toType) ? "CUSTOMER" : null));
    p.put("amount", Amount.bdt(minor));
    p.put("assertion", assertion);
    p.put("idempotencyKey", key);
    return p;
  }

  CoreCommand validate(String txnId, long minor, String assertion, String key) {
    return cmds.command("ValidateTransaction", txnId, txnId + ":validate", transfer("P2P", B, "WALLET", minor, assertion, key));
  }

  CoreCommand post(String txnId, long minor, String assertion, String key) {
    return cmds.command("PostTransfer", txnId, txnId + ":post", transfer("P2P", B, "WALLET", minor, assertion, key));
  }

  CoreEvent handle(CoreCommand c) {
    return cmds.valid(service.handle(c).orElseThrow(), c);
  }

  static void refused(CoreEvent e, String type) {
    assertThat(e.eventType()).isEqualTo(type);
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.FAILURE);
    assertThat(e.reasonCode()).isEqualTo("MW-AUTH-046");
  }

  @Test
  void aValidAssertionAuthorisesValidateAndThePostOfTheSameTxn() {
    String txn = CoreCommandsTestSupport.txnId();
    String key = UUID.randomUUID().toString();
    String a = TestAssertions.mint(TestAssertions.claims(A, "P2P", PAYER, PAYEE, 10_000, key).build());
    long before = balance(B);

    assertThat(handle(validate(txn, 10_000, a, key)).eventType()).isEqualTo("TransactionValidated");
    assertThat(handle(post(txn, 10_000, a, key)).eventType()).isEqualTo("TransferPosted");
    assertThat(balance(B)).isEqualTo(before + 10_000);
    assertThat(jdbc.queryForObject("select pin_failures from wallet where id = ?", Integer.class, A)).isZero();
  }

  @Test
  void aHoldCarriesTheAssertionToo() {
    String txn = CoreCommandsTestSupport.txnId();
    String key = UUID.randomUUID().toString();
    String a = TestAssertions.mint(
        TestAssertions.claims(A, "TOPUP", PAYER, new TxnHash.Party("MSISDN", "01711000009"), 2_000, key).build());
    CoreCommand hold = cmds.command("HoldFunds", txn, txn + ":hold", transfer("TOPUP", "01711000009", "MSISDN", 2_000, a, key));
    assertThat(handle(hold).eventType()).isEqualTo("FundsHeld");
  }

  @Test
  void aTamperedAmountIsRefused() {
    String key = UUID.randomUUID().toString();
    String a = TestAssertions.mint(TestAssertions.claims(A, "P2P", PAYER, PAYEE, 10_000, key).build());
    long before = balance(A);
    String txn = CoreCommandsTestSupport.txnId();
    refused(handle(post(txn, 90_000, a, key)), "TransferRejected");
    assertThat(balance(A)).isEqualTo(before);
  }

  @Test
  void anotherIdempotencyKeyIsRefused() {
    String a = TestAssertions.mint(TestAssertions.claims(A, "P2P", PAYER, PAYEE, 10_000, "key-1").build());
    refused(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, a, "key-2")), "TransactionRejected");
  }

  @Test
  void aWrongAudienceIsRefused() {
    String key = UUID.randomUUID().toString();
    String a = TestAssertions.mint(TestAssertions.claims(A, "P2P", PAYER, PAYEE, 10_000, key).audience("mw-app").build());
    refused(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, a, key)), "TransactionRejected");
  }

  @Test
  void anotherWalletsAssertionIsRefused() {
    String key = UUID.randomUUID().toString();
    String a = TestAssertions.mint(TestAssertions.claims(B, "P2P", PAYER, PAYEE, 10_000, key).build());
    refused(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, a, key)), "TransactionRejected");
  }

  @Test
  void aSignatureByAKeyNotInMwAuthsSetIsRefused() {
    String key = UUID.randomUUID().toString();
    JWTClaimsSet c = TestAssertions.claims(A, "P2P", PAYER, PAYEE, 10_000, key).build();
    refused(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, TestAssertions.sign(TestAssertions.FOREIGN, c, h -> h), key)),
        "TransactionRejected");
    String garbage = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.c2ln";
    refused(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, garbage, key)), "TransactionRejected");
  }

  @Test
  void anAssertionUsedByOneTxnIsRefusedForAnother() {
    String key = UUID.randomUUID().toString();
    String a = TestAssertions.mint(TestAssertions.claims(A, "P2P", PAYER, PAYEE, 10_000, key).build());
    assertThat(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, a, key)).eventType()).isEqualTo("TransactionValidated");
    refused(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, a, key)), "TransactionRejected");
  }

  @Test
  void anExpiredAssertionIsRefusedButTheSkewIsAllowed() {
    String key = UUID.randomUUID().toString();
    Instant now = Instant.now();
    String late = TestAssertions.mint(TestAssertions.claims(A, "P2P", PAYER, PAYEE, 10_000, key)
        .issueTime(Date.from(now.minusSeconds(120))).expirationTime(Date.from(now.minusSeconds(40))).build());
    refused(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, late, key)), "TransactionRejected");
    String withinSkew = TestAssertions.mint(TestAssertions.claims(A, "P2P", PAYER, PAYEE, 10_000, key)
        .issueTime(Date.from(now.minusSeconds(70))).expirationTime(Date.from(now.minusSeconds(10))).build());
    assertThat(handle(validate(CoreCommandsTestSupport.txnId(), 10_000, withinSkew, key)).eventType())
        .isEqualTo("TransactionValidated");
  }

  @Test
  void usedJtisArePurgedOnlyAfterTheRetention() {
    jdbc.update("insert into used_assertion (jti, txn_id, expires_at) values (?, ?, ?), (?, ?, ?)",
        "old-jti", "t1", Timestamp.from(Instant.now().minusSeconds(3 * 86_400)),
        "new-jti", "t2", Timestamp.from(Instant.now().minusSeconds(60)));
    purge.purge();
    assertThat(jdbc.queryForList("select jti from used_assertion where jti in ('old-jti', 'new-jti')", String.class))
        .containsExactly("new-jti");
  }
}
