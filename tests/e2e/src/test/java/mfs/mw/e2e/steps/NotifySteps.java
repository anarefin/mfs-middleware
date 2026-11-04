package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import java.time.Duration;
import java.util.List;
import mfs.mw.e2e.support.Mailbox;
import mfs.mw.e2e.support.Smsc;
import mfs.mw.e2e.support.Waits;

/**
 * Steps on customer notifications (mw-notify, tickets 24 and 25): what the SMSC simulator received
 * over SMPP for a number, and what the dev mail server (GreenMail) received over SMTP for an
 * address. {@code {txnId}} in the expected text stands for the scenario's transaction.
 */
public class NotifySteps {

  private final ScenarioState state;

  public NotifySteps(ScenarioState state) {
    this.state = state;
  }

  @Then("{word} gets the SMS {string}")
  public void getsTheSms(String msisdn, String expected) {
    getsTheSmsWithin(msisdn, expected, 30);
  }

  /** For states reached late (UNKNOWN after a partner timeout, COMPLETED by status inquiry). */
  @Then("{word} gets the SMS {string} within {int} seconds")
  public void getsTheSmsWithin(String msisdn, String expected, int seconds) {
    String text = expected.replace("{txnId}", state.txnId());
    // sent asynchronously: mw.txn.status.v1 -> mw-notify rules -> dispatcher -> SMPP
    List<Smsc.Sms> got =
        Waits.until(() -> Smsc.messagesTo(msisdn).stream().filter(m -> m.text().equals(text)).toList(),
            l -> !l.isEmpty() && l.getFirst().dlrStatus() != null, Duration.ofSeconds(seconds));
    assertThat(got).as("SMS to %s reading %s (received: %s)", msisdn, text, Smsc.messagesTo(msisdn)).hasSize(1);
    Smsc.Sms sms = got.getFirst();
    assertThat(sms.source()).isEqualTo("upay");
    assertThat(sms.dlrStatus()).isEqualTo("DELIVRD");
  }

  @Then("the SMS to {word} about the transaction was sent as {word} in {int} parts")
  public void sentAs(String msisdn, String encoding, int parts) {
    String txnId = state.txnId();
    List<Smsc.Sms> got = Smsc.messagesTo(msisdn).stream().filter(m -> m.text().contains(txnId)).toList();
    assertThat(got).as("SMS to %s about %s", msisdn, txnId).singleElement().satisfies(m -> {
      assertThat(m.encoding()).isEqualTo(encoding);
      assertThat(m.segments()).isEqualTo(parts);
    });
  }

  /**
   * The email receipt: exactly one message to the address with this subject, multipart/alternative
   * (text and HTML parts), naming the transaction.
   */
  @Then("{word} gets the email {string}")
  public void getsTheEmail(String address, String expectedSubject) {
    String subject = expectedSubject.replace("{txnId}", state.txnId());
    List<Mailbox.Mail> got =
        Waits.until(() -> Mailbox.messagesTo(address).stream().filter(m -> m.subject().equals(subject)).toList(),
            l -> !l.isEmpty(), Duration.ofSeconds(30));
    assertThat(got).as("email to %s with subject %s", address, subject).hasSize(1);
    Mailbox.Mail mail = got.getFirst();
    assertThat(mail.contentType()).startsWith("multipart/alternative");
    assertThat(mail.mime()).contains("text/plain").contains("text/html");
  }

  @Then("{word} gets no email about the transaction")
  public void getsNoEmail(String address) {
    String txnId = state.txnId();
    assertThat(Mailbox.messagesTo(address)).noneMatch(m -> m.subject().contains(txnId));
  }
}
