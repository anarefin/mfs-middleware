package mfs.mw.ussd.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The terminal states of spec 7.2, as this service reads them from {@code mw.txn.status.v1}. */
class TerminalStatusTest {

  @Test
  void theFourTerminalStatesAreRecognised() {
    assertThat(TerminalStatus.of("COMPLETED")).contains(TerminalStatus.COMPLETED);
    assertThat(TerminalStatus.of("FAILED")).contains(TerminalStatus.FAILED);
    assertThat(TerminalStatus.of("REJECTED")).contains(TerminalStatus.REJECTED);
    assertThat(TerminalStatus.of("REVERSED")).contains(TerminalStatus.REVERSED);
  }

  @Test
  void intermediateUnknownOrMissingStatesAreNotTerminal() {
    assertThat(TerminalStatus.of("PROCESSING")).isEmpty();
    assertThat(TerminalStatus.of("UNKNOWN")).isEmpty();
    assertThat(TerminalStatus.of("completed")).isEmpty();
    assertThat(TerminalStatus.of(null)).isEmpty();
  }

  @Test
  void onlyCompletedIsASuccess() {
    assertThat(TerminalStatus.COMPLETED.success()).isTrue();
    assertThat(TerminalStatus.FAILED.success()).isFalse();
    assertThat(TerminalStatus.REJECTED.success()).isFalse();
    assertThat(TerminalStatus.REVERSED.success()).isFalse();
  }

  @Test
  void aStatusMessageIsTerminalExactlyWhenItsStatusIs() {
    assertThat(message("COMPLETED").terminal()).isTrue();
    assertThat(message("REVERSED").terminalStatus()).contains(TerminalStatus.REVERSED);
    assertThat(message("PROCESSING").terminal()).isFalse();
    assertThat(message(null).terminal()).isFalse();
  }

  private static StatusMessage message(String status) {
    return new StatusMessage(
        1, "t-1", null, null, "USSD", "P2P", status, null, null, null, null, null, null, null, null,
        null, null, null, null);
  }
}
