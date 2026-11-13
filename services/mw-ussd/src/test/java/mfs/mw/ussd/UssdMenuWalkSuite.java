package mfs.mw.ussd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.ussd.messaging.OrchestratorStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The menu-walk suite (WP19 acceptance: the same walk passes over SMPP and over HTTP by changing
 * only config). Each subclass plays robi through one transport and starts its own context with
 * its own reply group; the tests here are transport-blind: dial, enter inputs, read the screens.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class UssdMenuWalkSuite {

  public static final String CUSTOMER = "8801712345678";
  static final String PAYEE = "01812345678";
  static final String PIN = "4826";

  /** A played session: the screens in order (dial-in answer first) and whether mw-ussd ended it. */
  public record Walk(List<String> screens, boolean ended) {

    String last() {
      return screens.getLast();
    }
  }

  private OrchestratorStub orchestrator;

  /** Dials *268# on robi as {@link #CUSTOMER} and sends {@code inputs} while the session is open. */
  protected abstract Walk walk(List<String> inputs);

  /** This subclass's {@code mw.ussd.instance-id}. */
  protected abstract String instanceId();

  @BeforeAll
  void startOrchestratorStub() {
    TestTopics.ensure("mw.txn.request.v1", "mw.txn.status.v1");
    orchestrator = new OrchestratorStub(TestTopics.bootstrap(), r -> {});
  }

  @AfterAll
  void stopOrchestratorStub() {
    orchestrator.close();
  }

  @BeforeEach
  void replyConsumerReady() {
    TestTopics.awaitGroupStable("mw-ussd.reply." + instanceId(), Duration.ofSeconds(60));
  }

  static List<String> p2p(boolean english, String amount, String pin) {
    List<String> in = new ArrayList<>();
    if (english) {
      in.add("9");
    }
    in.addAll(List.of("1", PAYEE, amount, "1", pin));
    return in;
  }

  @Test
  void p2pInEnglishCompletes() {
    Walk w = walk(p2p(true, "10", PIN));

    assertThat(w.screens().subList(0, 6))
        .containsExactly(
            "Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 মোবাইল রিচার্জ\n4 বিল পে\n9 English",
            "Bits\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা",
            "Enter recipient Bits number",
            "Enter amount (Tk)",
            "Send Tk 10.00 to 01812345678?\n1 Confirm\n2 Cancel",
            "Enter your PIN");
    assertThat(w.ended()).isTrue();
    assertThat(w.last()).startsWith("Tk 10.00 sent to 01812345678. TxnID ");
    assertThat(orchestrator.requests()).isNotEmpty();
  }

  @Test
  void p2pInBanglaCompletes() {
    Walk w = walk(p2p(false, "10", PIN));

    assertThat(w.ended()).isTrue();
    assertThat(w.last()).isEqualTo("01812345678 নম্বরে 10.00 টাকা পাঠানো হয়েছে।");
  }

  @Test
  void aTopUpOfTheOwnNumberReachesItsConfirmation() {
    Walk w = walk(List.of("9", "3", "1", "10"));

    assertThat(w.ended()).isFalse();
    assertThat(w.screens().subList(2, 5))
        .containsExactly(
            "Mobile Recharge\n1 Own number\n2 Other number",
            "Enter recharge amount (Tk)",
            "Recharge Tk 10.00 to 01712345678?\n1 Confirm\n2 Cancel");
  }

  @Test
  void aBadNumberIsAskedAgainAndTheSessionStaysOpen() {
    Walk w = walk(List.of("9", "1", "0171"));

    assertThat(w.ended()).isFalse();
    assertThat(w.last()).isEqualTo("Enter a valid mobile number.\nEnter recipient Bits number");
  }

  @Test
  void aCoreRefusalEndsWithItsMessage() {
    Walk w = walk(p2p(false, "30", PIN));

    assertThat(w.ended()).isTrue();
    assertThat(w.last()).isEqualTo("সেন্ড মানি ব্যর্থ: অপর্যাপ্ত ব্যালেন্স");
  }

  @Tag("slow-wait")
  @Test
  void noFinalAnswerWithinTheBudgetEndsWithBeingProcessed() {
    Walk w = walk(p2p(true, "20", PIN));

    assertThat(w.ended()).isTrue();
    assertThat(w.last()).isEqualTo("Your request is being processed. You will get the result by SMS.");
  }
}
