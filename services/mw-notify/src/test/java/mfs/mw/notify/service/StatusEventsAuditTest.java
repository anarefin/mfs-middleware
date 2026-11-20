package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import mfs.mw.notify.dto.AuditEvent;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.messaging.EventMetadata;
import mfs.mw.notify.messaging.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Ticket 130b (FRS AUD-01, AUD-04): one NOTIFICATION hop per final status of a transaction's message. */
class StatusEventsAuditTest {

  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
  static final Instant AT = Instant.parse("2026-11-03T08:15:35Z");

  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final StatusEvents events = new StatusEvents(outbox);

  static MessageRecord message(String txnId, String priority, String body) {
    return new MessageRecord(TXN + ":sms:P2P_OK", txnId, "corr-1", "SMS", "01711000001", "TXN_P2P_SENT", 1, "bn",
        priority, "Bits", body, null, 1, "QUEUED", 1, null, AT.plusSeconds(3600), null, null, null, null);
  }

  private AuditEvent audited() {
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(outbox).append(eq(StatusEvents.AUDIT_TOPIC), eq(TXN), any(EventMetadata.class), payload.capture());
    return (AuditEvent) payload.getValue();
  }

  @Test
  void aFinalStatusOfATransactionNoticeIsAnOutboundNotificationHopWithTheMaskedText() {
    events.publish(message(TXN, "TXN", "Sent 500 to 01811000002, Rahim. Ref " + TXN), NotifyStatus.DELIVERED, true, 1,
        "smsc-1", null, AT);

    AuditEvent e = audited();
    assertThat(e.action()).isEqualTo("notification.completed");
    assertThat(e.hop()).isEqualTo("NOTIFICATION");
    assertThat(e.direction()).isEqualTo("OUT");
    assertThat(e.outcome()).isEqualTo("SUCCESS");
    assertThat(e.txnId()).isEqualTo(TXN);
    assertThat(e.resource()).isEqualTo("txn/" + TXN);
    assertThat(e.service()).isEqualTo("mw-notify");
    assertThat(e.after()).containsEntry("channel", "SMS").containsEntry("status", "DELIVERED")
        .containsEntry("templateCode", "TXN_P2P_SENT");
    assertThat(e.payload()).singleElement().satisfies(p -> {
      assertThat(p.direction()).isEqualTo("OUT");
      assertThat(p.body()).contains("Sent 500").doesNotContain("01811000002");
    });
  }

  @Test
  void aFailedFinalStatusIsAFailureWithItsCode() {
    events.publish(message(TXN, "TXN", "x"), NotifyStatus.FAILED, true, 4, null, "MW-SYS-102", AT);

    AuditEvent e = audited();
    assertThat(e.outcome()).isEqualTo("FAILURE");
    assertThat(e.after()).containsEntry("errorCode", "MW-SYS-102").containsEntry("attempt", "4");
  }

  @Test
  void anOtpTextIsNeverAudited() {
    events.publish(message(TXN, "OTP", "Your code is 482913"), NotifyStatus.SENT, true, 1, "smsc-1", null, AT);

    assertThat(audited().payload()).isNull();
  }

  @Test
  void anAttemptOrAMessageWithoutATransactionIsNotAHop() {
    events.publish(message(TXN, "TXN", "x"), NotifyStatus.SENT, false, 1, "smsc-1", null, AT);
    events.publish(message(null, "TXN", "x"), NotifyStatus.SENT, true, 1, "smsc-1", null, AT);
    events.publish(message("not-a-txn-id", "TXN", "x"), NotifyStatus.SENT, true, 1, "smsc-1", null, AT);

    verify(outbox, never()).append(eq(StatusEvents.AUDIT_TOPIC), anyString(), any(EventMetadata.class), any());
  }
}
