package mfs.mw.notify.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The log never returns an OTP body, and every MSISDN is masked (FRS NOT-08). */
class NotificationLogItemTest {

  static final Instant AT = Instant.parse("2026-11-20T08:00:00Z");

  @Test
  void anOtpBodyIsAlwaysMasked() {
    NotificationLogItem item = NotificationLogItem.of("m-otp", "txn-1", "SMS", "01711000001", "OTP", "SENT",
        "LOGIN_OTP", "Your Bits code is 123456", AT);

    assertThat(item.body()).isEqualTo(NotificationLogItem.OTP_BODY);
    assertThat(item.body()).doesNotContain("123456");
    assertThat(item.msisdn()).isEqualTo("0171****001");
  }

  @Test
  void aTransactionalBodyIsKeptAndTheMsisdnIsMasked() {
    NotificationLogItem item = NotificationLogItem.of("m-txn", "txn-1", "SMS", "01711000001", "TXN", "SENT",
        "TXN_P2P_SENT", "You sent Tk 10.00", AT);

    assertThat(item.body()).isEqualTo("You sent Tk 10.00");
    assertThat(item.msisdn()).isEqualTo("0171****001");
    assertThat(item.msisdn()).doesNotContain("01711000001");
  }
}
