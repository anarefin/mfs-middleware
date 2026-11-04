package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.dto.TxnStatusEvent.Amount;
import mfs.mw.notify.dto.TxnStatusEvent.Party;
import mfs.mw.notify.entity.RuleRecord;
import org.junit.jupiter.api.Test;

/** Rules map txn status events to notification requests (spec 10.3). */
class RuleEngineTest {

  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";

  static final List<RuleRecord> RULES =
      List.of(
          new RuleRecord("p2p-sent", "mw.txn.status.v1", "status == 'COMPLETED' and txnType == 'P2P'",
              "TXN_P2P_SENT", "DEBIT_PARTY", "SMS", "TXN"),
          new RuleRecord("p2p-received", "mw.txn.status.v1",
              "status == 'COMPLETED' and txnType == 'P2P' and creditPartyId != debitPartyId",
              "TXN_P2P_RECEIVED", "CREDIT_PARTY", "SMS", "TXN"));

  private final RuleEngine engine = new RuleEngine("bn");

  static TxnStatusEvent p2p(String status, String from, String to) {
    return new TxnStatusEvent(TXN, "corr-1", "CUSTOMER_APP", "P2P", status,
        new Party("MSISDN", from, null), new Party("WALLET", from, "CUSTOMER"), new Party("WALLET", to, "CUSTOMER"),
        new Amount(50000, "BDT"), new Amount(500, "BDT"), "CORE-1", null, "en");
  }

  @Test
  void aCompletedP2pNotifiesSenderAndReceiverWithTheOtherPartyMasked() {
    List<NotificationRequest> requests = engine.requestsFor(p2p("COMPLETED", "01711000001", "01811000002"), RULES);

    assertThat(requests).hasSize(2);
    NotificationRequest sent = requests.get(0);
    assertThat(sent.messageId()).isEqualTo(TXN + ":sms:p2p-sent");
    assertThat(sent.txnId()).isEqualTo(TXN);
    assertThat(sent.correlationId()).isEqualTo("corr-1");
    assertThat(sent.recipient().msisdn()).isEqualTo("01711000001");
    assertThat(sent.templateCode()).isEqualTo("TXN_P2P_SENT");
    assertThat(sent.locale()).isEqualTo("en");
    assertThat(sent.channels()).containsExactly(Channel.SMS);
    assertThat(sent.priority()).isEqualTo(Priority.TXN);
    assertThat(sent.params())
        .containsEntry("amount", "500.00")
        .containsEntry("fee", "5.00")
        .containsEntry("counterparty", "0181****002")
        .containsEntry("txnId", TXN)
        .containsEntry("coreRef", "CORE-1");

    NotificationRequest received = requests.get(1);
    assertThat(received.recipient().msisdn()).isEqualTo("01811000002");
    assertThat(received.templateCode()).isEqualTo("TXN_P2P_RECEIVED");
    assertThat(received.params()).containsEntry("counterparty", "0171****001");
  }

  @Test
  void otherStatesMatchNoRule() {
    assertThat(engine.requestsFor(p2p("POSTING", "01711000001", "01811000002"), RULES)).isEmpty();
    assertThat(engine.requestsFor(p2p("FAILED", "01711000001", "01811000002"), RULES)).isEmpty();
  }

  @Test
  void aConditionOnPartiesCanSkipARule() {
    assertThat(engine.requestsFor(p2p("COMPLETED", "01711000001", "01711000001"), RULES))
        .extracting(NotificationRequest::templateCode)
        .containsExactly("TXN_P2P_SENT");
  }

  @Test
  void aRecipientThatIsNotAMobileNumberGetsNoSms() {
    TxnStatusEvent e = new TxnStatusEvent(TXN, "c", "CUSTOMER_APP", "P2P", "COMPLETED", null,
        new Party("BANK_ACCOUNT", "1234567890", null), new Party("WALLET", "01811000002", null),
        new Amount(100, "BDT"), null, null, null, null);

    List<NotificationRequest> requests = engine.requestsFor(e, RULES);

    assertThat(requests).extracting(NotificationRequest::templateCode).containsExactly("TXN_P2P_RECEIVED");
    assertThat(requests.getFirst().locale()).isEqualTo("bn");
    assertThat(requests.getFirst().params()).containsEntry("fee", "0.00").containsEntry("counterparty", "****7890");
  }

  @Test
  void aBrokenOrUnsafeConditionNeverMatches() {
    List<RuleRecord> rules =
        List.of(
            new RuleRecord("bad", "mw.txn.status.v1", "status ==", "X", "DEBIT_PARTY", "SMS", "TXN"),
            new RuleRecord("type", "mw.txn.status.v1", "T(java.lang.System).exit(1) == null", "X", "DEBIT_PARTY",
                "SMS", "TXN"),
            new RuleRecord("bean", "mw.txn.status.v1", "@dataSource != null", "X", "DEBIT_PARTY", "SMS", "TXN"),
            new RuleRecord("ctor", "mw.txn.status.v1", "new java.io.File('/') != null", "X", "DEBIT_PARTY", "SMS",
                "TXN"),
            new RuleRecord("notbool", "mw.txn.status.v1", "status", "X", "DEBIT_PARTY", "SMS", "TXN"));

    assertThat(engine.requestsFor(p2p("COMPLETED", "01711000001", "01811000002"), rules)).isEmpty();
  }
}
