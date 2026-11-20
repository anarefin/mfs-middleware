package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.dto.Menu;
import mfs.mw.admin.api.dto.MenuNode;
import mfs.mw.admin.api.dto.MenuOption;
import mfs.mw.admin.api.dto.NodeType;
import mfs.mw.admin.api.dto.Text;
import mfs.mw.admin.api.dto.ValidatorSpec;
import mfs.mw.admin.api.service.UssdPreview.Outcome;
import mfs.mw.admin.api.service.UssdPreview.ScreenLimits;
import mfs.mw.admin.api.service.UssdPreview.Step;
import org.junit.jupiter.api.Test;

/**
 * The server preview of a draft USSD menu (ticket 133b, CH-USSD-06): mw-ussd's screen paging as a
 * pure function and a walk over a scripted input sequence. Expected pages are the worked examples of
 * mw-ussd's {@code ScreenFormatter} and the console's {@code screenPages} tests.
 */
class UssdPreviewTest {

  static final ScreenLimits GP = new ScreenLimits(182, 70, false);
  static final ScreenLimits TELETALK = new ScreenLimits(182, 70, true);

  static Text t(String bn, String en) {
    return new Text(bn, en);
  }

  static MenuNode node(NodeType type, Text text, List<MenuOption> options) {
    return new MenuNode(type, text, options, null, null, null, null, null, null, null, null, null);
  }

  static MenuNode input(Text text, ValidatorSpec v, String store, String next) {
    return new MenuNode(NodeType.INPUT, text, null, v, store, next, null, null, null, null, null, null);
  }

  static MenuOption option(String key, Text label, String next) {
    return new MenuOption(key, label, next, false, null);
  }

  static final Map<String, Text> MESSAGES =
      Map.of(
          "invalid", t("ভুল ইনপুট।", "Invalid input."),
          "tooManyAttempts", t("অনেকবার ভুল", "Too many tries."),
          "expired", t("শেষ", "Expired"),
          "processing", t("প্রক্রিয়াধীন", "Processing"),
          "unavailable", t("বন্ধ", "Unavailable"),
          "more", t("0. পরবর্তী", "0. Next"));

  /** A long Bangla main menu (seven services) and a send-money path with a PIN. */
  static Menu longMenu() {
    List<String> bn = List.of("সেন্ড মানি", "ক্যাশ আউট", "মোবাইল রিচার্জ", "পেমেন্ট", "পে বিল", "অ্যাড মানি", "আমার অ্যাকাউন্ট");
    List<MenuOption> options = new java.util.ArrayList<>();
    for (int i = 0; i < bn.size(); i++) {
      options.add(option(String.valueOf(i + 1), t(bn.get(i), "Service " + (i + 1)), i == 0 ? "amount" : "done"));
    }
    return new Menu(
        "main", 1, "main", MESSAGES,
        Map.of(
            "main", node(NodeType.MENU, t("আপনার সেবা বেছে নিন", "Choose a service"), options),
            "amount", input(t("টাকার পরিমাণ দিন", "Enter amount (Tk)"),
                new ValidatorSpec("amount", null, "10", "25000", false, null), "amount", "pin"),
            "pin", input(t("আপনার পিন দিন", "Enter your PIN"), new ValidatorSpec("pin", null, null, null, false, null), null, "send"),
            "send", new MenuNode(NodeType.ACTION, null, null, null, null, null, "P2P",
                Map.of("creditParty", "=01712000002", "amount", "amount"), "ok", "failed", null, null),
            "ok", node(NodeType.END, t("সফল। TxnID {txnId}", "Sent Tk {amount}. TxnID {txnId}"), null),
            "failed", node(NodeType.END, t("ব্যর্থ", "Failed: {error}"), null),
            "done", node(NodeType.END, t("ধন্যবাদ", "Thank you"), null)));
  }

  @Test
  void aScreenThatFitsIsOnePage() {
    assertThat(UssdPreview.pages("Bits\n1 Send Money", GP, "0. Next", "00. Back")).containsExactly("Bits\n1 Send Money");
  }

  @Test
  void pagesSplitAtLineBreaksEachEndingWithNextAndBackTheLastWithBack() {
    assertThat(UssdPreview.pages("Pick one\n1 Send Money\n2 Cash Out\n3 Pay Bill", new ScreenLimits(30, 70, false),
        "0. Next", "00. Back"))
        .containsExactly("Pick one\n0. Next\n00. Back", "1 Send Money\n0. Next\n00. Back", "2 Cash Out\n3 Pay Bill\n00. Back");
  }

  @Test
  void truncateCutsAtTheLimit() {
    assertThat(UssdPreview.pages("x".repeat(200), TELETALK, "0. Next", "00. Back")).containsExactly("x".repeat(182));
  }

  @Test
  void aLongBanglaMenuPagesAtTheUcs2LimitAndShowsBack() {
    List<Step> steps = UssdPreview.walk(longMenu(), GP, "bn", List.of("0", "0", "00"), Outcome.COMPLETED);

    Step first = steps.getFirst();
    assertThat(first.input()).isNull();
    assertThat(first.pages()).isGreaterThan(1);
    assertThat(first.screen()).startsWith("আপনার সেবা বেছে নিন\n1 সেন্ড মানি").endsWith("\n0. পরবর্তী\n00. পেছনে");
    assertThat(steps).allSatisfy(s -> assertThat(s.screen().length()).isLessThanOrEqualTo(70));
    assertThat(steps.get(1).page()).isEqualTo(1);
    Step last = steps.get(first.pages() - 1);
    assertThat(last.screen()).endsWith("\n00. পেছনে").doesNotContain("পরবর্তী").contains("7 আমার অ্যাকাউন্ট");
    assertThat(steps.getLast().input()).isEqualTo("00");
    assertThat(steps.getLast().page()).isEqualTo(steps.get(steps.size() - 2).page() - 1);
  }

  @Test
  void aWalkFillsPlaceholdersMasksThePinAndEndsOnTheOutcome() {
    List<Step> steps = UssdPreview.walk(longMenu(), GP, "en", List.of("1", "5", "100", "12345", "9"), Outcome.COMPLETED);

    assertThat(steps).extracting(Step::input).containsExactly(null, "1", "5", "100", "****");
    assertThat(steps.get(2).screen()).startsWith("Invalid input.\nEnter amount (Tk)");
    assertThat(steps.getLast().screen()).isEqualTo("Sent Tk 100.00. TxnID " + UssdPreview.PREVIEW_TXN);
    assertThat(steps.getLast().ended()).isTrue();
  }

  @Test
  void aFailedActionShowsTheFailureNode() {
    List<Step> steps = UssdPreview.walk(longMenu(), GP, "en", List.of("1", "100", "1234"), Outcome.FAILED);

    assertThat(steps.getLast().screen()).startsWith("Failed: ");
    assertThat(steps.getLast().ended()).isTrue();
  }

  @Test
  void threeInvalidAnswersEndTheSession() {
    List<Step> steps = UssdPreview.walk(longMenu(), GP, "en", List.of("9", "9", "9", "1"), Outcome.COMPLETED);

    assertThat(steps).hasSize(4);
    assertThat(steps.getLast().screen()).isEqualTo("Too many tries.");
    assertThat(steps.getLast().ended()).isTrue();
  }
}
