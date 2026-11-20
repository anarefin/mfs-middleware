package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The SMSC keyword STOP is a promotional opt-out; anything else is not. */
class InboundSmsTest {

  @Test
  void stopIsTheOnlyKeywordAndTheNumberIsCanonical() {
    assertThat(InboundSms.stop("8801711000001", " STOP ")).contains("01711000001");
    assertThat(InboundSms.stop("+8801711000001", "stop")).contains("01711000001");
    assertThat(InboundSms.stop("01711000001", "STOP ALL")).isEmpty();
    assertThat(InboundSms.stop("not-a-number", "STOP")).isEqualTo(Optional.empty());
  }
}
