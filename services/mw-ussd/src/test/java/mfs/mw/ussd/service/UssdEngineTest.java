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
import mfs.mw.ussd.dto.Amount;
import mfs.mw.ussd.dto.Balance;
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
import mfs.mw.ussd.util.TestPinKeys;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The menu engine over the seeded menu, with an in-memory session store and a scripted flow. */
class UssdEngineTest {

  static final String CUSTOMER = "01712345678";
  static final String PAYEE = "01812345678";

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final BillDirectory BILLS = new UssdMenuWalkTest.FakeBills();
  static final Menu MENU = MenuParser.parseResource(JSON, "menus/main-v3.json");

  /** Sessions as Redis would hold them: serialised JSON, so a test can look inside. */
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
        "bn", 3, Map.of("robi", new UssdProperties.Mno(UssdProperties.Transport.HTTP, List.of("*247#"), gsm, 70,
            Duration.ofSeconds(120), overflow, "CON", "END", null)),
        Map.of(), Map.of());
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
        TestPinTransport.create(), new DynamicLists(p, BILLS), new Lookups(BILLS), p,
        Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));
  }

  UssdResponse dial(String session) {
    return engine.handle(new UssdRequest("robi", session, CUSTOMER, UssdEvent.BEGIN, "*247#"));
  }

  UssdResponse send(String session, String input) {
    return engine.handle(new UssdRequest("robi", session, CUSTOMER, UssdEvent.CONTINUE, input));
  }

  UssdResponse dialCode(String session, String serviceCode) {
    return engine.handle(new UssdRequest("robi", session, CUSTOMER, UssdEvent.BEGIN, "", serviceCode));
  }

  @Test
  void aBeginWithTheMnosOwnCodeOpensTheMainMenu() {
    UssdResponse r = dialCode("c1", "*247#");

    assertThat(r.end()).isFalse();
    assertThat(r.text()).startsWith("Bits\n1 সেন্ড মানি");
    assertThat(sessions.values).containsKey("ussd:robi:c1");
  }

  @Test
  void aBeginWithAnotherMnosCodeEndsAtOnceAndStoresNoSession() {
    UssdResponse r = dialCode("c2", "*268#");

    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("এই কোডটি এই নেটওয়ার্কে নেই। ডায়াল করুন *247#");
    assertThat(sessions.values).isEmpty();
  }

  @Test
  void theCodeIsComparedIgnoringWhitespace() {
    assertThat(dialCode("c3", " *247# ").end()).isFalse();
  }

  @Test
  void aBeginWithoutACodeIsAccepted() {
    assertThat(dialCode("c4", null).end()).isFalse();
    assertThat(dialCode("c5", "").end()).isFalse();
  }

  @Test
  void anMnoWithoutShortCodesAcceptsAnyCode() {
    engine = engine(new UssdProperties(
        "test-instance", "mw.txn.request.v1", "mw.txn.status.v1", Duration.ofSeconds(8), Duration.ofSeconds(30),
        "bn", 3, Map.of("robi", new UssdProperties.Mno(UssdProperties.Transport.HTTP, List.of(), 182, 70,
            Duration.ofSeconds(120), UssdProperties.Overflow.PAGE, "CON", "END", null)),
        Map.of(), Map.of()));

    assertThat(dialCode("c6", "*999#").end()).isFalse();
  }

  @Test
  void p2pMenuWalkInBangla() throws Exception {
    assertThat(dial("s1").text()).isEqualTo("Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 রিচার্জ\n4 বিল পে\n5 ব্যালেন্স\n9 English");
    assertThat(send("s1", "1").text()).isEqualTo("প্রাপকের Bits নম্বর দিন");
    assertThat(send("s1", PAYEE).text()).isEqualTo("টাকার পরিমাণ দিন");
    assertThat(send("s1", "500").text()).isEqualTo("01812345678 নম্বরে 500.00 টাকা পাঠাবেন?\n1 নিশ্চিত করুন\n2 বাতিল");
    assertThat(send("s1", "1").text()).isEqualTo("আপনার পিন দিন");

    UssdResponse done = send("s1", "48261");

    assertThat(done.end()).isTrue();
    assertThat(done.text())
        .isEqualTo("01812345678 নম্বরে 500.00 টাকা পাঠানো হয়েছে।");
    FlowRequest req = flow.requests.getFirst();
    assertThat(req.flow()).isEqualTo("P2P");
    assertThat(req.fields()).containsEntry("creditParty", PAYEE).containsEntry("amount", "500.00");
    assertThat(req.session().msisdn()).isEqualTo(CUSTOMER);
    assertThat(req.session().language()).isEqualTo("bn");
    assertThat(req.menuPath()).isEqualTo("main:1>p2p.recipient>p2p.amount>p2p.confirm:1>p2p.pin");
    assertThat(TestPinKeys.decrypt(req.pin())).isEqualTo("48261");
    assertThat(sessions.values).isEmpty();
  }

  @Test
  void p2pMenuWalkInEnglishAfterTheLanguageSwitch() {
    dial("s2");
    assertThat(send("s2", "9").text()).isEqualTo("Bits\n1 Send Money\n2 Cash Out\n3 Recharge\n4 Pay Bill\n5 Balance\n9 বাংলা");
    assertThat(send("s2", "1").text()).isEqualTo("Enter recipient Bits number");
    assertThat(send("s2", "8801812345678").text()).isEqualTo("Enter amount (Tk)");
    assertThat(send("s2", "120.5").text()).isEqualTo("Send Tk 120.50 to 01812345678?\n1 Confirm\n2 Cancel");
    assertThat(send("s2", "1").text()).isEqualTo("Enter your PIN");

    UssdResponse done = send("s2", "48261");

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
    assertThat(send("s3", "48a6").text()).isEqualTo("পিন ৫ অঙ্কের।\nআপনার পিন দিন");
    String stored = sessions.values.get("ussd:robi:s3");
    assertThat(stored).doesNotContain("48a6").contains("\"nodeId\":\"p2p.pin\"");

    send("s3", "48261");

    assertThat(sessions.values).doesNotContainKey("ussd:robi:s3");
    assertThat(flow.requests.getFirst().pin().ciphertext()).doesNotContain("48261");
  }

  @Test
  void sessionTtlIsTheMnoTimeoutPlusThirtySeconds() {
    dial("s4");
    assertThat(sessions.ttls.get("ussd:robi:s4")).isEqualTo(Duration.ofSeconds(150));
    assertThat(sessions.values.get("ussd:robi:s4")).contains("\"menuVersion\":3").contains("\"language\":\"bn\"");
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
    UssdResponse r = send("s9", "48261");
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
    assertThat(send("s10", "48261").text()).isEqualTo("সেন্ড মানি ব্যর্থ: অপর্যাপ্ত ব্যালেন্স");
  }

  static String clearPin(FlowRequest r) {
    try {
      return TestPinKeys.decrypt(r.pin());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Dials {@code s} and walks an English P2P of Tk 500 to the PIN prompt. */
  void toPinPrompt(String s) {
    dial(s);
    send(s, "9");
    send(s, "1");
    send(s, PAYEE);
    send(s, "500");
    assertThat(send(s, "1").text()).isEqualTo("Enter your PIN");
  }

  @Test
  void aWrongPinAsksForThePinAgainAsLongAsMwAuthSaysWrong() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.WRONG_PIN, null,
        new ErrorInfo("MW-AUTH-040", "Wrong PIN", null, false), "2");
    toPinPrompt("w1");

    UssdResponse again = send("w1", "11111");
    assertThat(again.end()).isFalse();
    assertThat(again.text()).isEqualTo("Wrong PIN. 2 tries left.\nEnter your PIN");
    assertThat(sessions.values.get("ussd:robi:w1")).contains("\"nodeId\":\"p2p.pin\"").doesNotContain("11111");

    flow.next = new FlowOutcome(FlowOutcome.Kind.WRONG_PIN, null,
        new ErrorInfo("MW-AUTH-040", "Wrong PIN", null, false), "1");
    assertThat(send("w1", "22222").text()).isEqualTo("Wrong PIN. 1 tries left.\nEnter your PIN");

    flow.next = new FlowOutcome(FlowOutcome.Kind.COMPLETED, "019a0000-0000-7000-8000-000000000003", null);
    UssdResponse done = send("w1", "48261");
    assertThat(done.end()).isTrue();
    assertThat(done.text()).startsWith("Tk 500.00 sent to 01812345678.");
    // every try reached the flow on the same menu path (the same idempotency key), each with its own PIN
    assertThat(flow.requests).hasSize(3).extracting(FlowRequest::menuPath).containsOnly(
        "main:9>main:1>p2p.recipient>p2p.amount>p2p.confirm:1>p2p.pin");
    assertThat(flow.requests).extracting(UssdEngineTest::clearPin)
        .containsExactly("11111", "22222", "48261");
  }

  @Test
  void aWrongPinIsNotCountedAsAnInvalidInput() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.WRONG_PIN, null, new ErrorInfo("MW-AUTH-040", "Wrong", null, false), "4");
    toPinPrompt("w2");
    for (int i = 0; i < 4; i++) {
      assertThat(send("w2", "11111").end()).isFalse();
    }
  }

  @Test
  void aTemporaryPinLockEndsTheSessionInItsLanguage() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.LOCKED, null, new ErrorInfo("MW-AUTH-041", "Locked", null, false));
    dial("l1");
    send("l1", "1");
    send("l1", PAYEE);
    send("l1", "500");
    send("l1", "1");
    UssdResponse r = send("l1", "48261");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("পিন সাময়িকভাবে লক। পরে আবার চেষ্টা করুন।");
    assertThat(sessions.values).doesNotContainKey("ussd:robi:l1");
  }

  @Test
  void aPermanentPinLockAndAWalletLockEndTheSession() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.LOCKED, null, new ErrorInfo("MW-AUTH-042", "Locked", null, false));
    toPinPrompt("l2");
    assertThat(send("l2", "48261").text()).isEqualTo("Your PIN is locked. Reset it in the app or call customer care.");

    flow.next = new FlowOutcome(FlowOutcome.Kind.LOCKED, null, new ErrorInfo("MW-AUTH-043", "Locked", null, false));
    toPinPrompt("l3");
    UssdResponse r = send("l3", "48261");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("Your account is locked. Please call customer care.");
  }

  @Test
  void mwAuthUnavailableEndsWithUnavailable() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.UNAVAILABLE, null, null);
    toPinPrompt("u1");
    UssdResponse r = send("u1", "48261");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("Service temporarily unavailable. Please try later.");
  }

  /** Ticket 99 (FRS PLT-013): the confirm node of a suspected business duplicate. */
  @Test
  void aSuspectedDuplicateAsksForThePinAgainAndTheConfirmedResendCarriesTheFlag() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.FAILED, "019a0000-0000-7000-8000-000000000009",
        new ErrorInfo("MW-VAL-024", "Duplicate suspected", null, false));
    toPinPrompt("d1");

    UssdResponse prompt = send("d1", "48261");
    assertThat(prompt.end()).isFalse();
    assertThat(prompt.text())
        .isEqualTo("You sent the same payment moments ago. Enter your PIN to send it again.\nEnter your PIN");
    assertThat(flow.requests.getLast().session().inputs()).doesNotContainKey(UssdEngine.CONFIRM_DUPLICATE);

    flow.next = new FlowOutcome(FlowOutcome.Kind.COMPLETED, "019a0000-0000-7000-8000-000000000010", null);
    UssdResponse done = send("d1", "48261");
    assertThat(done.end()).isTrue();
    assertThat(done.text()).startsWith("Tk 500.00 sent to 01812345678.");
    assertThat(flow.requests).hasSize(2).extracting(FlowRequest::menuPath).containsOnly(
        "main:9>main:1>p2p.recipient>p2p.amount>p2p.confirm:1>p2p.pin");
    assertThat(flow.requests.getLast().session().inputs()).containsEntry(UssdEngine.CONFIRM_DUPLICATE, "true");
  }

  @Test
  void aMenuWithoutThePinTextsUsesTheBuiltInOnes() {
    assertThat(UssdEngine.PIN_TEXTS.keySet())
        .containsExactlyInAnyOrder(Menu.WRONG_PIN, Menu.PIN_LOCKED, Menu.PIN_BLOCKED, Menu.WALLET_LOCKED);
    for (String key : UssdEngine.PIN_TEXTS.keySet()) {
      assertThat(MENU.message(key, "en")).isEqualTo(UssdEngine.PIN_TEXTS.get(key).en());
      assertThat(MENU.message(key, "bn")).isEqualTo(UssdEngine.PIN_TEXTS.get(key).bn());
    }
  }

  // --- BALANCE (ADR D26): 5 -> PIN -> the available balance ---

  static final Balance TWELVE_THOUSAND =
      new Balance(new Amount(1_200_000L, Amount.BDT), Instant.parse("2026-11-16T08:15:31Z"));

  @Test
  void balanceInBanglaShowsTheAvailableBalanceAfterThePin() throws Exception {
    flow.next = new FlowOutcome(FlowOutcome.Kind.COMPLETED, "019a0000-0000-7000-8000-000000000026", null, null,
        TWELVE_THOUSAND);
    dial("b1");
    assertThat(send("b1", "5").text()).isEqualTo("আপনার পিন দিন");

    UssdResponse done = send("b1", "48261");

    assertThat(done.end()).isTrue();
    assertThat(done.text()).isEqualTo("আপনার ব্যালেন্স ৳12,000.00");
    FlowRequest req = flow.requests.getFirst();
    assertThat(req.flow()).isEqualTo("BALANCE");
    assertThat(req.fields()).isEmpty();
    assertThat(req.menuPath()).isEqualTo("main:5>bal.pin");
    assertThat(TestPinKeys.decrypt(req.pin())).isEqualTo("48261");
    assertThat(sessions.values).isEmpty();
  }

  @Test
  void balanceInEnglish() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.COMPLETED, "t", null, null,
        new Balance(new Amount(5L, Amount.BDT), Instant.parse("2026-11-16T08:15:31Z")));
    dial("b2");
    send("b2", "9");
    assertThat(send("b2", "5").text()).isEqualTo("Enter your PIN");
    assertThat(send("b2", "48261").text()).isEqualTo("Your balance is Tk 0.05");
  }

  @Test
  void aWrongPinOnBalanceAsksForThePinAgain() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.WRONG_PIN, null,
        new ErrorInfo("MW-AUTH-040", "Wrong PIN", null, false), "2");
    dial("b3");
    send("b3", "9");
    send("b3", "5");

    UssdResponse again = send("b3", "11111");

    assertThat(again.end()).isFalse();
    assertThat(again.text()).isEqualTo("Wrong PIN. 2 tries left.\nEnter your PIN");
    flow.next = new FlowOutcome(FlowOutcome.Kind.COMPLETED, "t", null, null, TWELVE_THOUSAND);
    assertThat(send("b3", "48261").text()).isEqualTo("Your balance is Tk 12,000.00");
    assertThat(flow.requests).hasSize(2).extracting(FlowRequest::menuPath).containsOnly("main:9>main:5>bal.pin");
  }

  @Test
  void aLockOnBalanceEndsTheSession() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.LOCKED, null, new ErrorInfo("MW-AUTH-043", "Locked", null, false));
    dial("b4");
    send("b4", "5");
    UssdResponse r = send("b4", "48261");
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("আপনার অ্যাকাউন্ট লক। কাস্টমার কেয়ারে কল করুন।");
  }

  @Test
  void aRefusedBalanceShowsTheErrorInTheSessionLanguage() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.FAILED, "t",
        new ErrorInfo("MW-CORE-WALLET_NOT_FOUND", "Wallet not found", "ওয়ালেট পাওয়া যায়নি", false));
    dial("b5");
    send("b5", "5");
    assertThat(send("b5", "48261").text()).isEqualTo("ব্যালেন্স দেখা ব্যর্থ: ওয়ালেট পাওয়া যায়নি");
  }

  @Test
  void noBalanceWithinTheBudgetAsksToTryAgainAndPromisesNoSms() {
    for (FlowOutcome.Kind kind : List.of(FlowOutcome.Kind.PROCESSING, FlowOutcome.Kind.UNAVAILABLE)) {
      flow.next = new FlowOutcome(kind, kind == FlowOutcome.Kind.PROCESSING ? "t" : null, null);
      String s = "b6-" + kind;
      dial(s);
      send(s, "9");
      send(s, "5");

      UssdResponse r = send(s, "48261");

      assertThat(r.end()).as(kind.name()).isTrue();
      assertThat(r.text()).as(kind.name()).isEqualTo("Could not get your balance. Please try again.").doesNotContain("SMS");
    }
    flow.next = new FlowOutcome(FlowOutcome.Kind.PROCESSING, "t", null);
    dial("b7");
    send("b7", "5");
    assertThat(send("b7", "48261").text()).isEqualTo("ব্যালেন্স জানা যায়নি। আবার চেষ্টা করুন।");
  }

  @Test
  void aMenuWithoutTheBalanceTextUsesTheBuiltInOne() {
    assertThat(MENU.message(Menu.BALANCE_UNAVAILABLE, "en")).isEqualTo(UssdEngine.BALANCE_UNAVAILABLE_TEXT.en());
    assertThat(MENU.message(Menu.BALANCE_UNAVAILABLE, "bn")).isEqualTo(UssdEngine.BALANCE_UNAVAILABLE_TEXT.bn());
  }

  @Test
  void theCompletedBalanceIsNeverKeptInTheSession() {
    flow.next = new FlowOutcome(FlowOutcome.Kind.COMPLETED, "t", null, null, TWELVE_THOUSAND);
    dial("b8");
    send("b8", "5");
    send("b8", "48261");
    assertThat(sessions.values).doesNotContainKey("ussd:robi:b8");
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
