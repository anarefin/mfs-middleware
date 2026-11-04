package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import mfs.mw.notify.MutableClock;
import mfs.mw.notify.TestInfrastructure;
import mfs.mw.notify.client.FakeSmsSender;
import mfs.mw.notify.dto.SmsPart;
import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.dto.TxnStatusEvent.Amount;
import mfs.mw.notify.dto.TxnStatusEvent.PartnerRef;
import mfs.mw.notify.dto.TxnStatusEvent.Party;
import mfs.mw.notify.dto.TxnStatusEvent.TxnError;
import mfs.mw.notify.entity.RuleRecord;
import mfs.mw.notify.repository.RuleRepository;
import mfs.mw.notify.repository.TemplateRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The seeded rules and templates (V5, V7) for every MVP flow (ticket 25): a {@code mw.txn.status.v1}
 * event of each flow on success, failure and processing yields exactly the expected SMS texts to the
 * expected numbers, and the BILL_PAY / ADD_MONEY_BANK receipts by email to a party with a
 * registered address. Observed at the SMSC seam ({@link FakeSmsSender}) and in a real SMTP server
 * (in-process GreenMail).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=false",
      "mw.notify.smpp.enabled=false",
      "mw.notify.dispatch.enabled=false",
      "mw.notify.email.enabled=true"
    })
@Import({TestInfrastructure.class, FlowNotificationsIT.Fixtures.class})
class FlowNotificationsIT {

  static final String CUSTOMER = "01711000001";
  static final String CUSTOMER2 = "01711000002";
  static final String AGENT = "01811000001";
  static final String CUSTOMER_EMAIL = "customer01@example.test";

  static final int SMTP_PORT = EmailFlowIT.freePort();
  static final GreenMail MAIL = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

  static {
    MAIL.start();
  }

  @DynamicPropertySource
  static void smtp(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.host", () -> "127.0.0.1");
    registry.add("spring.mail.port", () -> SMTP_PORT);
  }

  @AfterAll
  static void stopMail() {
    MAIL.stop();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.parse("2026-11-04T08:00:00Z"));
    }

    @Bean
    @Primary
    FakeSmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }
  }

  private final TxnStatusService txnStatuses;
  private final DispatchService dispatcher;
  private final FakeSmsSender smsc;
  private final RuleRepository rules;
  private final TemplateRepository templates;
  private final JdbcTemplate jdbc;

  @Autowired
  FlowNotificationsIT(TxnStatusService txnStatuses, DispatchService dispatcher, FakeSmsSender smsc,
      RuleRepository rules, TemplateRepository templates, JdbcTemplate jdbc) {
    this.txnStatuses = txnStatuses;
    this.dispatcher = dispatcher;
    this.smsc = smsc;
    this.rules = rules;
    this.templates = templates;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() throws Exception {
    MAIL.purgeEmailFromAllMailboxes();
    smsc.reset();
    jdbc.update("update message set status = 'FAILED' where status in ('QUEUED', 'RETRY')");
    jdbc.update("insert into contact (party_id, email) values (?, ?) on conflict do nothing", CUSTOMER,
        CUSTOMER_EMAIL);
  }

  /** One flow state: the event and the SMS texts ({txnId} stands for its txnId) per number. */
  record Case(String txnType, String status, TxnStatusEvent event, Map<String, String> sms, String emailSubject) {
    @Override
    public String toString() {
      return txnType + " " + status;
    }
  }

  static Party msisdn(String id) {
    return new Party("MSISDN", id, null);
  }

  static Party wallet(String id, String type) {
    return new Party("WALLET", id, type);
  }

  static TxnStatusEvent event(String txnType, String status, Party initiator, Party debit, Party credit,
      long amountMinor, long feeMinor, PartnerRef partner) {
    TxnError error = "FAILED".equals(status) || "REJECTED".equals(status) ? new TxnError("MW-CORE-REJECTED") : null;
    return new TxnStatusEvent(UUID.randomUUID().toString(), "corr-" + txnType, "CUSTOMER_APP", txnType, status,
        initiator, debit, credit, new Amount(amountMinor, "BDT"), new Amount(feeMinor, "BDT"), "CORE-1", null, "en",
        partner, error);
  }

  static Map<String, String> sms(String... numberThenText) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i < numberThenText.length; i += 2) {
      out.put(numberThenText[i], numberThenText[i + 1]);
    }
    return out;
  }

  static Stream<Arguments> flows() {
    PartnerRef billagg = new PartnerRef("billagg", "BILL", "DESCO");
    PartnerRef c2wbank = new PartnerRef("c2wbank", "C2W", null);
    PartnerRef ocs = new PartnerRef("gp", "PREPAID", null);
    String processing = " is being processed. You will get an SMS with the result. TrxID {txnId}";
    List<Case> cases = List.of(
        // P2P: customer01 -> customer02, Tk 10, fee Tk 5
        new Case("P2P", "COMPLETED", event("P2P", "COMPLETED", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            wallet(CUSTOMER2, "CUSTOMER"), 1000, 500, null),
            sms(CUSTOMER, "You have sent Tk 10.00 to 0171****002. Fee Tk 5.00. TrxID {txnId}",
                CUSTOMER2, "You have received Tk 10.00 from 0171****001. TrxID {txnId}"), null),
        new Case("P2P", "FAILED", event("P2P", "FAILED", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            wallet(CUSTOMER2, "CUSTOMER"), 1000, 500, null),
            sms(CUSTOMER, "Send Money of Tk 10.00 to 0171****002 failed. TrxID {txnId}"), null),
        new Case("P2P", "UNKNOWN", event("P2P", "UNKNOWN", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            wallet(CUSTOMER2, "CUSTOMER"), 1000, 500, null),
            sms(CUSTOMER, "Your Send Money of Tk 10.00 to 0171****002" + processing), null),
        // CASH_IN: agent01 -> customer01, Tk 20, no fee
        new Case("CASH_IN", "COMPLETED", event("CASH_IN", "COMPLETED", msisdn(AGENT), wallet(AGENT, "AGENT"),
            wallet(CUSTOMER, "CUSTOMER"), 2000, 0, null),
            sms(AGENT, "Cash In of Tk 20.00 to 0171****001 is successful. TrxID {txnId}",
                CUSTOMER, "You have received Cash In of Tk 20.00 from agent 0181****001. TrxID {txnId}"), null),
        new Case("CASH_IN", "REJECTED", event("CASH_IN", "REJECTED", msisdn(AGENT), wallet(AGENT, "AGENT"),
            wallet(CUSTOMER, "CUSTOMER"), 2000, 0, null),
            sms(AGENT, "Cash In of Tk 20.00 to 0171****001 failed. TrxID {txnId}"), null),
        new Case("CASH_IN", "UNKNOWN", event("CASH_IN", "UNKNOWN", msisdn(AGENT), wallet(AGENT, "AGENT"),
            wallet(CUSTOMER, "CUSTOMER"), 2000, 0, null),
            sms(AGENT, "Cash In of Tk 20.00 to 0171****001" + processing), null),
        // CASH_OUT: customer01 -> agent01, Tk 20, fee Tk 10
        new Case("CASH_OUT", "COMPLETED", event("CASH_OUT", "COMPLETED", msisdn(CUSTOMER),
            wallet(CUSTOMER, "CUSTOMER"), wallet(AGENT, "AGENT"), 2000, 1000, null),
            sms(CUSTOMER, "Cash Out of Tk 20.00 to agent 0181****001 is successful. Fee Tk 10.00. TrxID {txnId}",
                AGENT, "You have received Cash Out of Tk 20.00 from 0171****001. TrxID {txnId}"), null),
        new Case("CASH_OUT", "FAILED", event("CASH_OUT", "FAILED", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            wallet(AGENT, "AGENT"), 2000, 1000, null),
            sms(CUSTOMER, "Cash Out of Tk 20.00 to agent 0181****001 failed. TrxID {txnId}"), null),
        new Case("CASH_OUT", "UNKNOWN", event("CASH_OUT", "UNKNOWN", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            wallet(AGENT, "AGENT"), 2000, 1000, null),
            sms(CUSTOMER, "Cash Out of Tk 20.00 to agent 0181****001" + processing), null),
        // TOPUP: customer01 recharges 01711000002, Tk 50, fee Tk 2
        new Case("TOPUP", "COMPLETED", event("TOPUP", "COMPLETED", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            msisdn(CUSTOMER2), 5000, 200, ocs),
            sms(CUSTOMER, "Recharge of Tk 50.00 to 0171****002 is successful. TrxID {txnId}",
                CUSTOMER2, "Your number has been recharged with Tk 50.00 by 0171****001. TrxID {txnId}"), null),
        new Case("TOPUP", "FAILED", event("TOPUP", "FAILED", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            msisdn(CUSTOMER2), 5000, 200, ocs),
            sms(CUSTOMER, "Recharge of Tk 50.00 to 0171****002 failed. TrxID {txnId}"), null),
        new Case("TOPUP", "UNKNOWN", event("TOPUP", "UNKNOWN", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            msisdn(CUSTOMER2), 5000, 200, ocs),
            sms(CUSTOMER, "Recharge of Tk 50.00 to 0171****002" + processing), null),
        // BILL_PAY: customer01 pays bill 1234567 of DESCO, Tk 667, fee Tk 3
        new Case("BILL_PAY", "COMPLETED", event("BILL_PAY", "COMPLETED", msisdn(CUSTOMER),
            wallet(CUSTOMER, "CUSTOMER"), new Party("BILLER_ACCOUNT", "1234567", null), 66700, 300, billagg),
            sms(CUSTOMER, "Bill payment of Tk 667.00 to DESCO for ****4567 is successful. Fee Tk 3.00. TrxID {txnId}"),
            "upay bill payment receipt, TrxID {txnId}"),
        new Case("BILL_PAY", "FAILED", event("BILL_PAY", "FAILED", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            new Party("BILLER_ACCOUNT", "1234567", null), 66700, 300, billagg),
            sms(CUSTOMER, "Bill payment of Tk 667.00 to DESCO for ****4567 failed. TrxID {txnId}"), null),
        new Case("BILL_PAY", "UNKNOWN", event("BILL_PAY", "UNKNOWN", msisdn(CUSTOMER), wallet(CUSTOMER, "CUSTOMER"),
            new Party("BILLER_ACCOUNT", "1234567", null), 66700, 300, billagg),
            sms(CUSTOMER, "Bill payment of Tk 667.00 to DESCO for ****4567" + processing), null),
        // ADD_MONEY_BANK: bank account 2001234567890 at c2wbank -> customer01's own wallet, Tk 1000
        new Case("ADD_MONEY_BANK", "COMPLETED", event("ADD_MONEY_BANK", "COMPLETED", msisdn(CUSTOMER),
            new Party("BANK_ACCOUNT", "2001234567890", null), wallet(CUSTOMER, "CUSTOMER"), 100000, 0, c2wbank),
            sms(CUSTOMER, "Tk 1000.00 has been added to your upay account from c2wbank account ****7890. TrxID {txnId}"),
            "upay add money receipt, TrxID {txnId}"),
        new Case("ADD_MONEY_BANK", "FAILED", event("ADD_MONEY_BANK", "FAILED", msisdn(CUSTOMER),
            new Party("BANK_ACCOUNT", "2001234567890", null), wallet(CUSTOMER, "CUSTOMER"), 100000, 0, c2wbank),
            sms(CUSTOMER, "Add Money of Tk 1000.00 from c2wbank account ****7890 failed. TrxID {txnId}"), null),
        new Case("ADD_MONEY_BANK", "UNKNOWN", event("ADD_MONEY_BANK", "UNKNOWN", msisdn(CUSTOMER),
            new Party("BANK_ACCOUNT", "2001234567890", null), wallet(CUSTOMER, "CUSTOMER"), 100000, 0, c2wbank),
            sms(CUSTOMER, "Add Money of Tk 1000.00 from c2wbank account ****7890" + processing), null));
    return cases.stream().map(Arguments::of);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("flows")
  void eachFlowStateSendsTheExpectedMessages(Case c) throws Exception {
    String txnId = c.event().txnId();

    txnStatuses.onStatus(c.event());
    dispatcher.dispatchDue();

    Map<String, String> expected = new LinkedHashMap<>();
    c.sms().forEach((number, text) -> expected.put(number, text.replace("{txnId}", txnId)));
    assertThat(smsTexts()).isEqualTo(expected);

    MimeMessage[] mails = MAIL.getReceivedMessages();
    if (c.emailSubject() == null) {
      assertThat(mails).isEmpty();
    } else {
      assertThat(mails).hasSize(1);
      assertThat(mails[0].getAllRecipients()).extracting(Object::toString).containsExactly(CUSTOMER_EMAIL);
      assertThat(mails[0].getSubject()).isEqualTo(c.emailSubject().replace("{txnId}", txnId));
      Map<String, String> parts = EmailFlowIT.parts(mails[0]);
      assertThat(parts.get("text/plain")).contains(txnId).doesNotContain("{{");
      assertThat(parts.get("text/html")).contains(txnId).doesNotContain("{{");
    }
  }

  @Test
  void aReceiptEmailNeedsARegisteredAddress() {
    TxnStatusEvent e = event("BILL_PAY", "COMPLETED", msisdn(CUSTOMER2), wallet(CUSTOMER2, "CUSTOMER"),
        new Party("BILLER_ACCOUNT", "1234567", null), 66700, 300, new PartnerRef("billagg", "BILL", "DESCO"));

    txnStatuses.onStatus(e);
    dispatcher.dispatchDue();

    assertThat(smsTexts()).containsOnlyKeys(CUSTOMER2);
    assertThat(MAIL.getReceivedMessages()).isEmpty();
  }

  @Test
  void everySeededRuleHasAnActiveTemplateInBanglaAndEnglish() {
    Instant now = Instant.parse("2026-11-04T08:00:00Z");
    List<RuleRecord> all = rules.enabledFor(TxnStatusService.TOPIC);
    assertThat(all).extracting(RuleRecord::ruleId).contains("p2p-failed", "cashin-sent", "cashout-received",
        "topup-processing", "billpay-success-email", "addmoney-success-email");
    for (RuleRecord r : all) {
      for (String locale : List.of("bn", "en")) {
        assertThat(templates.findActive(r.templateCode(), r.channel(), locale, now))
            .as("%s %s %s", r.templateCode(), r.channel(), locale)
            .isPresent();
      }
    }
  }

  /** Text per destination number: parts in order, UDH removed, GSM-7 (unpacked) or UCS-2 decoded. */
  Map<String, String> smsTexts() {
    Map<String, String> out = new LinkedHashMap<>();
    for (FakeSmsSender.Sent s : smsc.sent()) {
      out.merge(s.part().destination(), text(s.part()), String::concat);
    }
    return out;
  }

  static String text(SmsPart part) {
    byte[] bytes = part.shortMessage();
    if (part.udhi()) {
      bytes = Arrays.copyOfRange(bytes, (bytes[0] & 0xff) + 1, bytes.length);
    }
    // The seeded English texts use only characters whose GSM 03.38 code equals ASCII.
    return part.dataCoding() == 0x08
        ? new String(bytes, StandardCharsets.UTF_16BE)
        : new String(bytes, StandardCharsets.US_ASCII);
  }
}
