package mfs.mw.orchestrator.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TxnRequest;
import org.junit.jupiter.api.Test;

/** The wallet parties of the wallet history (ADR D25): which side of which transaction is a wallet. */
class SearchKeysTest {

  private static List<String> parties(TxnRequest r) {
    return SearchKeys.walletParties(r).stream()
        .map(p -> p.direction() + " " + p.key().type() + ":" + p.key().id())
        .toList();
  }

  private static TxnRequest withParties(TxnRequest r, Party debit, Party credit) {
    return new TxnRequest(
        r.schemaVersion(), r.txnId(), r.idempotencyKey(), r.correlationId(), r.channel(), r.txnType(),
        r.initiator(), debit, credit, r.amount(), r.partner(), r.originalTxnId(), r.assertion(), r.locale(),
        r.metadata(), r.attributes(), r.requestedAt());
  }

  @Test
  void aP2pIsOutForThePayerAndInForThePayee() {
    assertThat(parties(TestData.p2p(TestData.txnId(), "k", 100)))
        .containsExactly("OUT WALLET:01711000001", "IN WALLET:01711000002");
  }

  @Test
  void aMerchantPaymentIndexesTheMerchantWalletAsIn() {
    assertThat(parties(TestData.merchantPay(TestData.txnId(), "k", 100, "01911000001")))
        .containsExactly("OUT WALLET:01711000001", "IN WALLET:01911000001");
    TxnRequest typed = withParties(
        TestData.merchantPay(TestData.txnId(), "k", 100, "01911000001"),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("MERCHANT", "01911000001", null, null));
    assertThat(parties(typed)).containsExactly("OUT WALLET:01711000001", "IN WALLET:01911000001");
  }

  @Test
  void aBulkChildIndexesOnlyTheCustomerNotTheOrg() {
    assertThat(parties(TestData.bulkChild(TestData.txnId(), "k", 100, "01711000003")))
        .containsExactly("IN WALLET:01711000003");
  }

  @Test
  void addMoneyIsInForTheWalletAndTheBankAccountIsNoWallet() {
    assertThat(parties(TestData.addMoneyBank(TestData.txnId(), "k", 100, "1234567890123")))
        .containsExactly("IN WALLET:01711000001");
  }

  @Test
  void aTopupOrBillPayIsOutOnlyTheTopupNumberAndBillAreNoWallets() {
    assertThat(parties(TestData.topup(TestData.txnId(), "k", 100, "01711000002")))
        .containsExactly("OUT WALLET:01711000001");
    assertThat(parties(TestData.billPay(TestData.txnId(), "k", 100, "1234567")))
        .containsExactly("OUT WALLET:01711000001");
  }

  @Test
  void aUssdCreditTaggedCustomerIsAWalletOfAnyWalletType() {
    TxnRequest ussd = withParties(
        TestData.p2p(TestData.txnId(), "k", 100),
        new Party("WALLET", "01711000001", null, null),
        new Party("WALLET", "8801711000002", null, "CUSTOMER"));
    assertThat(parties(ussd)).containsExactly("OUT WALLET:01711000001", "IN WALLET:8801711000002");
  }

  @Test
  void aWalletOnBothSidesCountsOnceAsOut() {
    TxnRequest self = withParties(
        TestData.p2p(TestData.txnId(), "k", 100),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        new Party("WALLET", "+8801711000001", null, "CUSTOMER"));
    assertThat(parties(self)).containsExactly("OUT WALLET:01711000001");
  }

  @Test
  void theKeyNeverShowsTheId() {
    assertThat(SearchKeys.walletParties(TestData.p2p(TestData.txnId(), "k", 100)).toString())
        .doesNotContain("01711000001");
  }

  /** ADR D26 / D25: a BALANCE check moves no money and is no entry of the wallet history. */
  @Test
  void aBalanceCheckHasNoWalletParties() {
    assertThat(SearchKeys.walletParties(TestData.balance(TestData.txnId(), "k", "01711000001"))).isEmpty();
  }
}
