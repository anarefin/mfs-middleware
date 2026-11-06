package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.dto.ErrorInfo;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.dto.Text;
import mfs.mw.ussd.dto.UssdEvent;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import mfs.mw.ussd.exception.BillPayRefusedException;
import mfs.mw.ussd.exception.BillPayUnavailableException;
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.dto.Amount;
import mfs.mw.ussd.mapper.MenuParser;
import mfs.mw.ussd.util.PinCipher;
import mfs.mw.ussd.util.TestPinKeys;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Menu walks over the seeded menu for every MVP path besides P2P (ticket 28): customer CASH_OUT to
 * an agent, TOPUP of the own and of another number, BILL_PAY with the live biller list and the bill
 * fetch; each in Bangla (default) and English (option 9).
 */
class UssdMenuWalkTest {

  static final String CUSTOMER = "01712345678";
  static final String AGENT = "01812345678";
  static final String OTHER = "01912345678";
  static final String TXN = "019a0000-0000-7000-8000-000000000001";

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Menu MENU = MenuParser.parseResource(JSON, "menus/main-v2.json");

  /** The biller catalogue and bill fetch of mw-adapter-billpay, scripted. */
  static final class FakeBills implements BillDirectory {
    final List<String> fetched = new ArrayList<>();
    RuntimeException failure;

    @Override
    public List<Biller> billers() {
      return List.of(
          new Biller("DESCO", "Dhaka Electric Supply Company", "ELECTRICITY", "billagg"),
          new Biller("WASA", "Dhaka WASA", "WATER", "billagg"));
    }

    @Override
    public Bill fetch(String billerCode, String billNumber) {
      fetched.add(billerCode + "/" + billNumber);
      if (failure != null) {
        throw failure;
      }
      return new Bill(billerCode, billNumber, new Amount(66_700L, Amount.BDT), "2026-11-14", "2026-11");
    }
  }

  static UssdProperties props() {
    return new UssdProperties(
        "test-instance", "mw.txn.request.v1", "mw.txn.status.v1", Duration.ofSeconds(8), Duration.ofSeconds(30),
        "bn", 3, new UssdProperties.Pin("core-pin-test", TestPinKeys.pem()),
        Map.of(
            "robi", new UssdProperties.Mno(UssdProperties.Transport.HTTP, List.of("*268#"), 182, 70,
                Duration.ofSeconds(120), UssdProperties.Overflow.PAGE, "CON", "END", null),
            "bl", new UssdProperties.Mno(UssdProperties.Transport.HTTP, List.of("*268#"), 182, 70,
                Duration.ofSeconds(120), UssdProperties.Overflow.PAGE, "CON", "END", "banglalink")),
        Map.of("operators", List.of(
            new UssdProperties.ListItem("gp", new Text("গ্রামীণফোন", "Grameenphone")),
            new UssdProperties.ListItem("robi", new Text("রবি", "Robi")),
            new UssdProperties.ListItem("banglalink", new Text("বাংলালিংক", "Banglalink")),
            new UssdProperties.ListItem("teletalk", new Text("টেলিটক", "Teletalk")))));
  }

  final UssdEngineTest.InMemorySessions sessions = new UssdEngineTest.InMemorySessions();
  final UssdEngineTest.ScriptedFlow flow = new UssdEngineTest.ScriptedFlow();
  final FakeBills bills = new FakeBills();
  final UssdEngine engine = engine();

  UssdEngine engine() {
    UssdProperties p = props();
    MenuCatalog catalog = new MenuCatalog() {
      @Override
      public Menu active(String mno) {
        return MENU;
      }

      @Override
      public Menu version(String menuId, int version) {
        return MENU;
      }
    };
    return new UssdEngine(
        catalog, sessions, new InputValidator(), new ScreenFormatter(), flow,
        new PinCipher("core-pin-test", TestPinKeys.pem()), new DynamicLists(p, bills), new Lookups(bills), p,
        Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));
  }

  UssdResponse dial(String session) {
    return engine.handle(new UssdRequest("robi", session, CUSTOMER, UssdEvent.BEGIN, "*268#"));
  }

  UssdResponse send(String session, String input) {
    return engine.handle(new UssdRequest("robi", session, CUSTOMER, UssdEvent.CONTINUE, input));
  }

  @Test
  void theMainMenuOffersEveryUssdFlowInBothLanguages() {
    assertThat(dial("m").text())
        .isEqualTo("Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 মোবাইল রিচার্জ\n4 বিল পে\n9 English");
    assertThat(send("m", "9").text())
        .isEqualTo("Bits\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা");
  }

  // --- CASH_OUT (customer to agent) ---

  @Test
  void cashOutToAnAgentInBangla() throws Exception {
    dial("c1");
    assertThat(send("c1", "2").text()).isEqualTo("এজেন্টের Bits নম্বর দিন");
    assertThat(send("c1", AGENT).text()).isEqualTo("টাকার পরিমাণ দিন");
    assertThat(send("c1", "500").text())
        .isEqualTo("এজেন্ট 01812345678: 500.00 টাকা ক্যাশ আউট?\n1 নিশ্চিত করুন\n2 বাতিল");
    assertThat(send("c1", "1").text()).isEqualTo("আপনার পিন দিন");

    UssdResponse done = send("c1", "4826");

    assertThat(done.end()).isTrue();
    assertThat(done.text()).isEqualTo("এজেন্ট 01812345678 থেকে 500.00 টাকা ক্যাশ আউট হয়েছে।");
    FlowRequest req = flow.requests.getFirst();
    assertThat(req.flow()).isEqualTo("CASH_OUT");
    assertThat(req.fields()).containsEntry("creditParty", AGENT).containsEntry("amount", "500.00");
    assertThat(req.menuPath()).isEqualTo("main:2>co.agent>co.amount>co.confirm:1>co.pin");
    assertThat(TestPinKeys.decrypt(req.pin())).isEqualTo("4826");
    assertThat(sessions.values).isEmpty();
  }

  @Test
  void cashOutToAnAgentInEnglish() {
    dial("c2");
    send("c2", "9");
    assertThat(send("c2", "2").text()).isEqualTo("Enter agent Bits number");
    assertThat(send("c2", CUSTOMER).text()).isEqualTo("Enter a valid mobile number.\nEnter agent Bits number");
    assertThat(send("c2", "8801812345678").text()).isEqualTo("Enter amount (Tk)");
    assertThat(send("c2", "20").text()).isEqualTo("Enter an amount from Tk 50 to 25000.\nEnter amount (Tk)");
    assertThat(send("c2", "500").text()).isEqualTo("Cash out Tk 500.00 at agent 01812345678?\n1 Confirm\n2 Cancel");
    assertThat(send("c2", "1").text()).isEqualTo("Enter your PIN");
    assertThat(send("c2", "4826").text()).isEqualTo("Tk 500.00 cashed out at agent 01812345678. TxnID " + TXN);
    assertThat(flow.requests.getFirst().session().language()).isEqualTo("en");
  }

  // --- TOPUP ---

  @Test
  void topUpOfTheOwnNumberUsesTheDiallingMnoInBangla() {
    dial("t1");
    assertThat(send("t1", "3").text()).isEqualTo("মোবাইল রিচার্জ\n1 নিজের নম্বর\n2 অন্য নম্বর");
    assertThat(send("t1", "1").text()).isEqualTo("রিচার্জের পরিমাণ দিন");
    assertThat(send("t1", "50").text())
        .isEqualTo("01712345678 নম্বরে 50.00 টাকা রিচার্জ করবেন?\n1 নিশ্চিত করুন\n2 বাতিল");
    assertThat(send("t1", "1").text()).isEqualTo("আপনার পিন দিন");

    UssdResponse done = send("t1", "4826");

    assertThat(done.end()).isTrue();
    assertThat(done.text()).isEqualTo("01712345678 নম্বরে 50.00 টাকা রিচার্জ হয়েছে।");
    FlowRequest req = flow.requests.getFirst();
    assertThat(req.flow()).isEqualTo("TOPUP");
    assertThat(req.fields())
        .containsEntry("creditParty", CUSTOMER)
        .containsEntry("amount", "50.00")
        .containsEntry("partnerId", "robi")
        .containsEntry("productCode", "PREPAID");
    assertThat(req.menuPath()).isEqualTo("main:3>tu.whom:1>tu.amount>tu.confirm:1>tu.pin");
  }

  /**
   * The MNO key is {@code bl} (spec 9.2 {@code ussd.mno.<gp|robi|teletalk|bl>}); its top-up
   * operator is mw-adapter-telco's partnerId {@code banglalink} ({@code mw.ussd.mno.bl.operator}).
   */
  @Test
  void topUpOfTheOwnNumberOnBlGoesToTheBanglalinkOperator() {
    engine.handle(new UssdRequest("bl", "t9", CUSTOMER, UssdEvent.BEGIN, "*268#"));
    for (String input : new String[] {"3", "1", "50", "1", "4826"}) {
      engine.handle(new UssdRequest("bl", "t9", CUSTOMER, UssdEvent.CONTINUE, input));
    }

    FlowRequest req = flow.requests.getFirst();
    assertThat(req.flow()).isEqualTo("TOPUP");
    assertThat(req.fields()).containsEntry("creditParty", CUSTOMER).containsEntry("partnerId", "banglalink");
    assertThat(req.session().mno()).isEqualTo("bl");
  }

  @Test
  void topUpOfAnotherNumberAsksForTheOperatorInEnglish() {
    dial("t2");
    send("t2", "9");
    assertThat(send("t2", "3").text()).isEqualTo("Mobile Recharge\n1 Own number\n2 Other number");
    assertThat(send("t2", "2").text()).isEqualTo("Enter mobile number");
    assertThat(send("t2", "12345").text()).isEqualTo("Enter a valid mobile number.\nEnter mobile number");
    assertThat(send("t2", OTHER).text())
        .isEqualTo("Select operator\n1 Grameenphone\n2 Robi\n3 Banglalink\n4 Teletalk");
    assertThat(send("t2", "7").text())
        .isEqualTo("Invalid input.\nSelect operator\n1 Grameenphone\n2 Robi\n3 Banglalink\n4 Teletalk");
    assertThat(send("t2", "1").text()).isEqualTo("Enter recharge amount (Tk)");
    assertThat(send("t2", "5000").text())
        .isEqualTo("Enter an amount from Tk 10 to 1000.\nEnter recharge amount (Tk)");
    assertThat(send("t2", "20").text()).isEqualTo("Recharge Tk 20.00 to 01912345678?\n1 Confirm\n2 Cancel");
    send("t2", "1");

    assertThat(send("t2", "4826").text()).isEqualTo("Tk 20.00 recharged to 01912345678. TxnID " + TXN);
    FlowRequest req = flow.requests.getFirst();
    assertThat(req.fields())
        .containsEntry("creditParty", OTHER)
        .containsEntry("partnerId", "gp")
        .containsEntry("productCode", "PREPAID");
    assertThat(req.menuPath()).isEqualTo("main:9>main:3>tu.whom:2>tu.number>tu.operator:1>tu.amount>tu.confirm:1>tu.pin");
  }

  @Test
  void topUpOfAnotherNumberInBangla() {
    dial("t3");
    send("t3", "3");
    send("t3", "2");
    assertThat(send("t3", OTHER).text())
        .isEqualTo("অপারেটর বাছাই করুন\n1 গ্রামীণফোন\n2 রবি\n3 বাংলালিংক\n4 টেলিটক");
    send("t3", "4");
    send("t3", "20");
    send("t3", "1");
    assertThat(send("t3", "4826").text()).isEqualTo("01912345678 নম্বরে 20.00 টাকা রিচার্জ হয়েছে।");
    assertThat(flow.requests.getFirst().fields()).containsEntry("partnerId", "teletalk");
  }

  @Test
  void aTopUpRefusalShowsThePartnerMessage() {
    flow.next = new mfs.mw.ussd.dto.FlowOutcome(mfs.mw.ussd.dto.FlowOutcome.Kind.FAILED, "t",
        new ErrorInfo("MW-PTR-GP-TOPUP_REJECTED", "Top-up rejected by the operator", null, false));
    dial("t4");
    send("t4", "9");
    send("t4", "3");
    send("t4", "1");
    send("t4", "20");
    send("t4", "1");
    assertThat(send("t4", "4826").text()).isEqualTo("Recharge failed: Top-up rejected by the operator");
  }

  // --- BILL_PAY ---

  @Test
  void billPayWithTheBillerListAndTheFetchedAmountInBangla() {
    dial("b1");
    assertThat(send("b1", "4").text()).isEqualTo("বিলার বাছাই করুন\n1 DESCO\n2 WASA");
    assertThat(send("b1", "1").text()).isEqualTo("DESCO বিল নম্বর দিন");
    assertThat(send("b1", "1234567").text())
        .isEqualTo("DESCO বিল 1234567: 667.00 টাকা দিবেন?\n1 নিশ্চিত করুন\n2 বাতিল");
    assertThat(bills.fetched).containsExactly("DESCO/1234567");
    assertThat(send("b1", "1").text()).isEqualTo("আপনার পিন দিন");

    UssdResponse done = send("b1", "4826");

    assertThat(done.end()).isTrue();
    assertThat(done.text()).isEqualTo("DESCO বিল 1234567: 667.00 টাকা দেওয়া হয়েছে।");
    FlowRequest req = flow.requests.getFirst();
    assertThat(req.flow()).isEqualTo("BILL_PAY");
    assertThat(req.fields())
        .containsEntry("creditParty", "1234567")
        .containsEntry("amount", "667.00")
        .containsEntry("partnerId", "billagg")
        .containsEntry("billerCode", "DESCO");
    assertThat(req.menuPath()).isEqualTo("main:4>bp.biller:1>bp.number>bp.confirm:1>bp.pin");
  }

  @Test
  void billPayInEnglish() {
    dial("b2");
    send("b2", "9");
    assertThat(send("b2", "4").text()).isEqualTo("Select biller\n1 DESCO\n2 WASA");
    assertThat(send("b2", "2").text()).isEqualTo("Enter WASA bill number");
    assertThat(send("b2", "12").text()).isEqualTo("Enter a valid bill number.\nEnter WASA bill number");
    assertThat(send("b2", "1234567").text()).isEqualTo("Pay Tk 667.00 for WASA bill 1234567?\n1 Confirm\n2 Cancel");
    send("b2", "1");
    assertThat(send("b2", "4826").text()).isEqualTo("Tk 667.00 paid for WASA bill 1234567. TxnID " + TXN);
    assertThat(flow.requests.getFirst().fields()).containsEntry("billerCode", "WASA");
  }

  @Test
  void anUnknownBillIsRefusedWithTheAggregatorMessageAndAskedAgain() {
    bills.failure = new BillPayRefusedException(502,
        new ErrorEnvelope("MW-PTR-BILLAGG-BILL_NOT_FOUND", "Bill not found", "বিল পাওয়া যায়নি", null, "c", false, List.of()));
    dial("b3");
    send("b3", "4");
    send("b3", "1");
    assertThat(send("b3", "1234404").text()).isEqualTo("বিল পাওয়া যায়নি\nDESCO বিল নম্বর দিন");
    assertThat(sessions.values.get("ussd:robi:b3")).contains("\"nodeId\":\"bp.number\"");
    bills.failure = null;
    assertThat(send("b3", "1234567").text()).startsWith("DESCO বিল 1234567: 667.00 টাকা দিবেন?");
    assertThat(flow.requests).isEmpty();
  }

  @Test
  void anUnknownBillInEnglishShowsTheEnglishMessage() {
    bills.failure = new BillPayRefusedException(502,
        new ErrorEnvelope("MW-PTR-BILLAGG-BILL_NOT_FOUND", "Bill not found", "বিল পাওয়া যায়নি", null, "c", false, List.of()));
    dial("b4");
    send("b4", "9");
    send("b4", "4");
    send("b4", "1");
    assertThat(send("b4", "1234404").text()).isEqualTo("Bill not found\nEnter DESCO bill number");
  }

  @Test
  void aBillServiceOutageEndsTheSessionAsUnavailable() {
    bills.failure = new BillPayUnavailableException("down", null);
    dial("b5");
    send("b5", "9");
    send("b5", "4");
    send("b5", "1");
    UssdResponse r = send("b5", "1234567");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("Service temporarily unavailable. Please try later.");
    assertThat(sessions.values).isEmpty();
  }

  @Test
  void everyBanglaScreenOfTheSeededMenuFitsOneUcs2Screen() {
    // walk every path in Bangla and check each screen is a single page (no "0 আরও")
    List<String> screens = new ArrayList<>();
    screens.add(dial("w1").text());
    for (String in : List.of("2", AGENT, "25000", "1", "4826")) {
      screens.add(send("w1", in).text());
    }
    dial("w2");
    for (String in : List.of("3", "2", OTHER, "3", "1000", "1", "4826")) {
      screens.add(send("w2", in).text());
    }
    dial("w3");
    for (String in : List.of("4", "1", "12345678901234", "1", "4826")) {
      screens.add(send("w3", in).text());
    }
    dial("w4");
    for (String in : List.of("1", AGENT, "25000", "1", "4826")) {
      screens.add(send("w4", in).text());
    }
    assertThat(screens).allSatisfy(s -> assertThat(s).hasSizeLessThanOrEqualTo(70).doesNotEndWith("0 আরও"));
  }
}
