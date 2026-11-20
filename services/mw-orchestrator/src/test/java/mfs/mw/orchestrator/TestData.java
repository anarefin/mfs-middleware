package mfs.mw.orchestrator;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TxnRequest;

/** Fake, obviously non-real request fixtures. */
public final class TestData {

  public static final Instant NOW = Instant.parse("2026-11-03T08:15:30Z");

  private TestData() {}

  /** A UUIDv7-shaped id (version 7, variant 8), as the contract's TxnId pattern requires. */
  public static String txnId() {
    String u = UUID.randomUUID().toString();
    return u.substring(0, 14) + "7" + u.substring(15, 19) + "8" + u.substring(20);
  }

  /** A TOPUP of {@code msisdn} (operator gp, prepaid) paid from customer 01711000001's wallet. */
  public static TxnRequest topup(String txnId, String idemKey, long amountMinor, String msisdn) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "TOPUP",
        new Party("MSISDN", "01711000001", null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("MSISDN", msisdn, null, null),
        Amount.bdt(amountMinor),
        new PartnerRef("gp", "PREPAID", null),
        null,
        ASSERTION,
        "en",
        null,
        null,
        NOW);
  }

  /** A BILL_PAY of DESCO bill {@code billNumber} through aggregator billagg, from 01711000001. */
  public static TxnRequest billPay(String txnId, String idemKey, long amountMinor, String billNumber) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "BILL_PAY",
        new Party("MSISDN", "01711000001", null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("BILLER_ACCOUNT", billNumber, null, null),
        Amount.bdt(amountMinor),
        new PartnerRef("billagg", "BILL", "DESCO"),
        null,
        ASSERTION,
        "en",
        null,
        null,
        NOW);
  }

  /** A MERCHANT_PAY of {@code amountMinor} from customer 01711000001 to merchant wallet {@code merchant}. */
  public static TxnRequest merchantPay(String txnId, String idemKey, long amountMinor, String merchant) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "MERCHANT_PAY",
        new Party("MSISDN", "01711000001", null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("WALLET", merchant, null, "MERCHANT"),
        Amount.bdt(amountMinor),
        null,
        null,
        ASSERTION,
        "en",
        null,
        null,
        NOW);
  }

  /**
   * An ADD_MONEY_BANK (C2W) of {@code amountMinor} from bank account {@code account} at bank
   * c2wbank into customer wallet 01711000001, authorised with the wallet's PIN (ticket 23).
   */
  public static TxnRequest addMoneyBank(String txnId, String idemKey, long amountMinor, String account) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "ADD_MONEY_BANK",
        new Party("MSISDN", "01711000001", null, null),
        new Party("BANK_ACCOUNT", account, null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        Amount.bdt(amountMinor),
        new PartnerRef("c2wbank", "C2W", null),
        null,
        ASSERTION,
        "en",
        null,
        null,
        NOW);
  }

  /**
   * A W2B (transfer to bank, ticket 117c) of {@code amountMinor} from customer wallet 01711000001 to
   * bank account {@code account} at {@code bankPartner} (e.g. {@code bank-dbbl}), with the wallet's PIN.
   */
  public static TxnRequest w2b(String txnId, String idemKey, long amountMinor, String bankPartner, String account) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "W2B",
        new Party("MSISDN", "01711000001", null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("BANK_ACCOUNT", account, null, null),
        Amount.bdt(amountMinor),
        new PartnerRef(bankPartner, "W2B", null),
        null,
        ASSERTION,
        "en",
        null,
        null,
        NOW);
  }

  /**
   * A REVERSAL of {@code originalTxnId} asked by operations (channel SYSTEM): the parties are the
   * original P2P's, swapped; no PIN.
   */
  public static TxnRequest reversal(String txnId, String idemKey, String originalTxnId, long amountMinor) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "SYSTEM",
        "REVERSAL",
        new Party("ORG", "bits-ops", null, null),
        new Party("WALLET", "01711000002", null, "CUSTOMER"),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        Amount.bdt(amountMinor),
        null,
        originalTxnId,
        null,
        "en",
        null,
        Map.of("reason", "customer dispute"),
        NOW);
  }

  /**
   * A BALANCE check (ADR D26) of customer wallet {@code wallet}: amount 0, the wallet on both
   * sides, authorised with mw-auth's assertion.
   */
  public static TxnRequest balance(String txnId, String idemKey, String wallet) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "BALANCE",
        new Party("MSISDN", wallet, null, null),
        new Party("WALLET", wallet, null, "CUSTOMER"),
        new Party("WALLET", wallet, null, "CUSTOMER"),
        Amount.bdt(0),
        null,
        null,
        ASSERTION,
        "en",
        null,
        null,
        NOW);
  }

  /** {@code r} with other parties and amount (refusal tests). */
  public static TxnRequest with(TxnRequest r, Party debit, Party credit, long amountMinor) {
    return new TxnRequest(
        r.schemaVersion(), r.txnId(), r.idempotencyKey(), r.correlationId(), r.channel(), r.txnType(),
        r.initiator(), debit, credit, Amount.bdt(amountMinor), r.partner(), r.originalTxnId(),
        r.assertion(), r.locale(), r.metadata(), r.attributes(), r.requestedAt());
  }

  public static TxnRequest p2p(String txnId, String idemKey, long amountMinor) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "CUSTOMER_APP",
        "P2P",
        new Party("MSISDN", "01711000001", null, null),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("WALLET", "01711000002", null, "CUSTOMER"),
        Amount.bdt(amountMinor),
        null,
        null,
        ASSERTION,
        "en",
        Map.of("deviceId", "dev-test-1"),
        null,
        NOW);
  }

  /**
   * One child of an org bulk disbursement (ticket 34): org ORG001 (wallet 01311000001) pays
   * customer {@code msisdn}; authorised by the portal's maker-checker, so no PIN.
   */
  public static TxnRequest bulkChild(String txnId, String idemKey, long amountMinor, String msisdn) {
    return new TxnRequest(
        1,
        txnId,
        idemKey,
        "corr-" + txnId.substring(0, 8),
        "ORG_PORTAL",
        "BULK_DISBURSE",
        new Party("ORG", "ORG001", null, null),
        new Party("ORG", "01311000001", null, null),
        new Party("WALLET", msisdn, null, "CUSTOMER"),
        Amount.bdt(amountMinor),
        null,
        null,
        null,
        "en",
        null,
        Map.of("parentTxnId", "01933b8e-0000-7000-8000-000000000001", "lineNo", "2"),
        NOW);
  }

  /** A fake compact JWS in the shape of mw-auth's transaction assertion (ticket 71). */
  public static final String ASSERTION =
      "eyJhbGciOiJFUzI1NiIsImtpZCI6ImFzc2VydGlvbi1zaWduLnYxIn0.eyJqdGkiOiJ4In0.ZmFrZS1zaWduYXR1cmU";

  /** {@code r} with the fake assertion (every TestData request carries it since ticket 72). */
  public static TxnRequest withAssertion(TxnRequest r) {
    return new TxnRequest(
        r.schemaVersion(), r.txnId(), r.idempotencyKey(), r.correlationId(), r.channel(), r.txnType(),
        r.initiator(), r.debitParty(), r.creditParty(), r.amount(), r.partner(), r.originalTxnId(),
        ASSERTION, r.locale(), r.metadata(), r.attributes(), r.requestedAt());
  }
}
