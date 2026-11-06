package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.ErrorInfo;
import mfs.mw.ussd.dto.FlowOutcome;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.dto.UssdEvent;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import mfs.mw.ussd.dto.UssdSession;
import mfs.mw.ussd.mapper.MenuParser;
import mfs.mw.ussd.repository.UssdSessionStore;
import mfs.mw.ussd.util.PinCipher;
import mfs.mw.ussd.util.TestPinKeys;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The menu engine over the seeded menu, with an in-memory session store and a scripted flow. */
class UssdEngineTest {

  static final String CUSTOMER = "01712345678";
  static final String PAYEE = "01812345678";

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final BillDirectory BILLS = new UssdMenuWalkTest.FakeBills();
  static final Menu MENU = MenuParser.parseResource(JSON, "menus/main-v2.json");

  /** Sessions as Valkey would hold them: serialised JSON, so a test can look inside. */
  static final class InMemorySessions implements UssdSessionStore {
    final Map<String, String> values = new HashMap<>();
    final Map<String, Duration> ttls = new HashMap<>();

    @Override
    public Optional<UssdSession> find(String mno, String sessionId) {
      return Optional.ofNullable(values.get(UssdSessionStore.key(mno, sessionId)))
          .map(v -> JSON.readValue(v, UssdSession.class));
    }

    @Override
    public void save(UssdSession s, Duration ttl) {
      values.put(UssdSessionStore.key(s.mno(), s.sessionId()), JSON.writeValueAsString(s));
      ttls.put(UssdSessionStore.key(s.mno(), s.sessionId()), ttl);
    }

    @Override
    public void delete(String mno, String sessionId) {
      values.remove(UssdSessionStore.key(mno, sessionId));
    }
  }

  static final class ScriptedFlow implements TransactionFlow {
    final List<FlowRequest> requests = new ArrayList<>();
    FlowOutcome next = new FlowOutcome(FlowOutcome.Kind.COMPLETED, "019a0000-0000-7000-8000-000000000001", null);

    @Override
    public FlowOutcome submit(FlowRequest request) {
      requests.add(request);
      return next;
    }
  }

  static UssdProperties props(UssdProperties.Overflow overflow, int gsm) {
    return new UssdProperties(
        "test-instance", "mw.txn.request.v1", "mw.txn.status.v1", Duration.ofSeconds(8), Duration.ofSeconds(30),
        "bn", 3, new UssdProperties.Pin("core-pin-test", TestPinKeys.pem()),
        Map.of("robi", new UssdProperties.Mno(UssdProperties.Transport.HTTP, List.of("*247#"), gsm, 70,
            Duration.ofSeconds(120), overflow, "CON", "END", null)),
        Map.of());
  }

  final InMemorySessions sessions = new InMemorySessions();
  final ScriptedFlow flow = new ScriptedFlow();
  UssdEngine engine = engine(props(UssdProperties.Overflow.PAGE, 182));

  UssdEngine engine(UssdProperties p) {
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
        new PinCipher("core-pin-test", TestPinKeys.pem()), new DynamicLists(p, BILLS), new Lookups(BILLS), p,
        Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));
  }

  UssdResponse dial(String session) {
    return engine.handle(new UssdRequest("robi", session, CUSTOMER, UssdEvent.BEGIN, "*247#"));
  }

  UssdResponse send(String session, String input) {
    return engine.handle(new UssdRequest("robi", session, CUSTOMER, UssdEvent.CONTINUE, input));
  }

  @Test
  void p2pMenuWalkInBangla() throws Exception {
    assertThat(dial("s1").text()).isEqualTo("Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 মোবাইল রিচার্জ\n4 বিল পে\n9 English");
    assertThat(send("s1", "1").text()).isEqualTo("প্রাপকের Bits নম্বর দিন");
    assertThat(send("s1", PAYEE).text()).isEqualTo("টাকার পরিমাণ দিন");
    assertThat(send("s1", "500").text()).isEqualTo("01812345678 নম্বরে 500.00 টাকা পাঠাবেন?\n1 নিশ্চিত করুন\n2 বাতিল");
    assertThat(send("s1", "1").text()).isEqualTo("আপনার পিন দিন");

    UssdResponse done = send("s1", "4826");

    assertThat(done.end()).isTrue();
    assertThat(done.text())
        .isEqualTo("01812345678 নম্বরে 500.00 টাকা পাঠানো হয়েছে।");
    FlowRequest req = flow.requests.getFirst();
    assertThat(req.flow()).isEqualTo("P2P");
    assertThat(req.fields()).containsEntry("creditParty", PAYEE).containsEntry("amount", "500.00");
    assertThat(req.session().msisdn()).isEqualTo(CUSTOMER);
    assertThat(req.session().language()).isEqualTo("bn");
    assertThat(req.menuPath()).isEqualTo("main:1>p2p.recipient>p2p.amount>p2p.confirm:1>p2p.pin");
    assertThat(TestPinKeys.decrypt(req.pin())).isEqualTo("4826");
    assertThat(sessions.values).isEmpty();
  }

  @Test
  void p2pMenuWalkInEnglishAfterTheLanguageSwitch() {
    dial("s2");
    assertThat(send("s2", "9").text()).isEqualTo("Bits\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা");
    assertThat(send("s2", "1").text()).isEqualTo("Enter recipient Bits number");
    assertThat(send("s2", "8801812345678").text()).isEqualTo("Enter amount (Tk)");
    assertThat(send("s2", "120.5").text()).isEqualTo("Send Tk 120.50 to 01812345678?\n1 Confirm\n2 Cancel");
    assertThat(send("s2", "1").text()).isEqualTo("Enter your PIN");

    UssdResponse done = send("s2", "4826");

    assertThat(done.end()).isTrue();
    assertThat(done.text()).isEqualTo("Tk 120.50 sent to 01812345678. TxnID 019a0000-0000-7000-8000-000000000001");
    assertThat(flow.requests.getFirst().session().language()).isEqualTo("en");
  }

  @Test
  void theStoredSessionNeverHoldsThePin() {
    dial("s3");
    send("s3", "1");
    send("s3", PAYEE);
    send("s3", "500");
    send("s3", "1");
    // a mistyped PIN keeps the session at the PIN prompt: look inside it
    assertThat(send("s3", "48a6").text()).isEqualTo("পিন ৪ অঙ্কের।\nআপনার পিন দিন");
    String stored = sessions.values.get("ussd:robi:s3");
    assertThat(stored).doesNotContain("48a6").contains("\"nodeId\":\"p2p.pin\"");

    send("s3", "4826");

    assertThat(sessions.values).doesNotContainKey("ussd:robi:s3");
    assertThat(flow.requests.getFirst().pin().ciphertext()).doesNotContain("4826");
  }

  @Test
  void sessionTtlIsTheMnoTimeoutPlusThirtySeconds() {
    dial("s4");
    assertThat(sessions.ttls.get("ussd:robi:s4")).isEqualTo(Duration.ofSeconds(150));
    assertThat(sessions.values.get("ussd:robi:s4")).contains("\"menuVersion\":2").contains("\"language\":\"bn\"");
  }

  @Test
  void badMsisdnOrAmountIsRefusedAndPromptedAgain() {
    dial("s5");
    send("s5", "9");
    send("s5", "1");
    assertThat(send("s5", "12345").text()).isEqualTo("Enter a valid mobile number.\nEnter recipient Bits number");
    assertThat(send("s5", CUSTOMER).text()).isEqualTo("Enter a valid mobile number.\nEnter recipient Bits number");
    assertThat(send("s5", PAYEE).text()).isEqualTo("Enter amount (Tk)");
    assertThat(send("s5", "5").text()).isEqualTo("Enter an amount from Tk 10 to 25000.\nEnter amount (Tk)");
    assertThat(send("s5", "abc").text()).isEqualTo("Enter an amount from Tk 10 to 25000.\nEnter amount (Tk)");
    assertThat(send("s5", "100").text()).startsWith("Send Tk 100.00 to 01812345678?");
    assertThat(flow.requests).isEmpty();
  }

  @Test
  void anUnknownMenuChoiceIsPromptedAgain() {
    dial("s6");
    assertThat(send("s6", "7").text()).startsWith("ভুল ইনপুট।\nBits\n1 সেন্ড মানি").endsWith("0 আরও");
  }

  @Test
  void tooManyInvalidInputsEndTheSession() {
    dial("s7");
    send("s7", "1");
    send("s7", "x");
    send("s7", "y");
    UssdResponse r = send("s7", "z");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("অনেকবার ভুল ইনপুট। আবার চেষ্টা করুন।");
    assertThat(sessions.values).isEmpty();
  }

  @Test
  void cancelEndsWithoutATransaction() {
    dial("s8");
    send("s8", "1");
    send("s8", PAYEE);
    send("s8", "500");
    UssdResponse r = send("s8", "2");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("বাতিল করা হয়েছে।");
    assertThat(flow.requests).isEmpty();
  }

  @Test
  void anAnswerAfterTheSyncBudgetEndsWithBeingProcessed() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.PROCESSING, "019a0000-0000-7000-8000-000000000002", null);
    dial("s9");
    send("s9", "9");
    send("s9", "1");
    send("s9", PAYEE);
    send("s9", "500");
    send("s9", "1");
    UssdResponse r = send("s9", "4826");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("Your request is being processed. You will get the result by SMS.");
  }

  @Test
  void aRefusalShowsTheCoreMessageInTheSessionLanguage() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.FAILED, "t",
        new ErrorInfo("MW-CORE-INSUFFICIENT_BALANCE", "Insufficient balance", "অপর্যাপ্ত ব্যালেন্স", false));
    dial("s10");
    send("s10", "1");
    send("s10", PAYEE);
    send("s10", "500");
    send("s10", "1");
    assertThat(send("s10", "4826").text()).isEqualTo("সেন্ড মানি ব্যর্থ: অপর্যাপ্ত ব্যালেন্স");
  }

  @Test
  void aContinueWithoutASessionEndsWithExpired() {
    UssdResponse r = send("nope", "1");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("সেশন শেষ হয়েছে। আবার ডায়াল করুন।");
  }

  @Test
  void aSessionIsOnlyContinuedForTheSameSubscriber() {
    dial("s11");
    UssdResponse r = engine.handle(new UssdRequest("robi", "s11", PAYEE, UssdEvent.CONTINUE, "1"));
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("সেশন শেষ হয়েছে। আবার ডায়াল করুন।");
  }

  @Test
  void abortAndEndDropTheSession() {
    dial("s12");
    engine.handle(new UssdRequest("robi", "s12", CUSTOMER, UssdEvent.ABORT, null));
    assertThat(sessions.values).isEmpty();
    dial("s13");
    engine.handle(new UssdRequest("robi", "s13", CUSTOMER, UssdEvent.END, null));
    assertThat(sessions.values).isEmpty();
  }

  @Test
  void aScreenLongerThanTheMnoMaximumIsPagedWithMore() {
    engine = engine(props(UssdProperties.Overflow.PAGE, 25));
    dial("s14");
    send("s14", "9");
    send("s14", "1");
    send("s14", PAYEE);
    UssdResponse first = send("s14", "500");
    assertThat(first.end()).isFalse();
    assertThat(first.text()).hasSizeLessThanOrEqualTo(25).endsWith("\n0 More");
    StringBuilder all = new StringBuilder(first.text().replace("\n0 More", ""));
    UssdResponse next = send("s14", "0");
    while (next.text().endsWith("\n0 More")) {
      assertThat(next.text()).hasSizeLessThanOrEqualTo(25);
      all.append('\n').append(next.text().replace("\n0 More", ""));
      next = send("s14", "0");
    }
    all.append('\n').append(next.text());
    // nothing is lost: the pages hold the whole screen (split at line breaks or spaces)
    assertThat(all.toString().replaceAll("\\s", ""))
        .isEqualTo("SendTk500.00to01812345678?1Confirm2Cancel");
    // the confirm node still takes its answer after the last page
    assertThat(send("s14", "1").text()).isEqualTo("Enter your PIN");
  }

  @Test
  void aScreenLongerThanTheMnoMaximumIsTruncatedWhenSoConfigured() {
    engine = engine(props(UssdProperties.Overflow.TRUNCATE, 25));
    dial("s15");
    send("s15", "9");
    send("s15", "1");
    send("s15", PAYEE);
    UssdResponse r = send("s15", "500");
    assertThat(r.text()).isEqualTo("Send Tk 500.00 to 0181234");
    assertThat(send("s15", "1").text()).isEqualTo("Enter your PIN");
  }
}
