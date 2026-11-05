package mfs.mw.checkout.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The final transaction states of spec 7.2, and nothing else, are terminal. */
class TerminalStatusTest {

  @Test
  void theFourFinalStatesAreTerminal() {
    assertThat(TerminalStatus.of("COMPLETED")).contains(TerminalStatus.COMPLETED);
    assertThat(TerminalStatus.of("FAILED")).contains(TerminalStatus.FAILED);
    assertThat(TerminalStatus.of("REJECTED")).contains(TerminalStatus.REJECTED);
    assertThat(TerminalStatus.of("REVERSED")).contains(TerminalStatus.REVERSED);
  }

  @Test
  void runningUnknownAndMissingStatesAreNot() {
    for (String s : List.of("RECEIVED", "POSTING", "UNKNOWN", "MANUAL_REVIEW", "completed", "")) {
      assertThat(TerminalStatus.of(s)).as(s).isEmpty();
    }
    assertThat(TerminalStatus.of(null)).isEmpty();
  }

  @Test
  void aStatusMessageIsTerminalExactlyWhenItsStatusIs() {
    assertThat(message("COMPLETED").terminal()).isTrue();
    assertThat(message("POSTING").terminal()).isFalse();
    assertThat(message(null).terminal()).isFalse();
  }

  static StatusMessage message(String status) {
    return new StatusMessage(
        1, null, null, null, null, null, status, null, null, null, null, null, null, null, null, null, null, null,
        null);
  }
}
