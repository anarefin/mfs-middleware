package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import mfs.mw.recon.dto.TrustPosition;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.repository.ReconStore;
import org.junit.jupiter.api.Test;

/** REC-07: the e-money total against the trust bank's MT940 closing balance (ticket 127). */
class TrustPositionTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 19);
  private static final Instant NOW = Instant.parse("2026-11-19T19:00:00Z");

  private final ReconStore store = mock(ReconStore.class);
  private final ExceptionEvents events = mock(ExceptionEvents.class);
  private final TrustPositionService service =
      new TrustPositionService(store, "trustbank", Clock.fixed(NOW, ZoneOffset.UTC), events);

  @Test
  void equalBalancesGiveZeroDifferenceAndNoException() {
    when(store.trustClosing(DAY, "TRUST_MT940")).thenReturn(1_234_567_89L);

    TrustPosition position = service.compute(DAY, 1_234_567_89L);

    assertThat(position.differenceMinor()).isZero();
    assertThat(position.exceptionId()).isNull();
    verify(store, never()).insertStandaloneException(any(), anyString(), anyString(), anyString(), anyString(), any(),
        any(), any());
    verify(store).saveTrustPosition(DAY, 1_234_567_89L, 1_234_567_89L, 0L, null, NOW);
  }

  @Test
  void aGapRaisesOneAmountMismatchWithTheExactAmount() {
    when(store.trustClosing(DAY, "TRUST_MT940")).thenReturn(1_000_000L);

    TrustPosition position = service.compute(DAY, 1_000_150L);

    assertThat(position.differenceMinor()).isEqualTo(-150L);
    assertThat(position.exceptionId()).isNotNull();
    verify(store).insertStandaloneException(position.exceptionId(), "AMOUNT_MISMATCH", "TRUST_POSITION", "finance",
        "trustbank", 1_000_000L, 1_000_150L, NOW);
    verify(store).saveTrustPosition(DAY, 1_000_150L, 1_000_000L, -150L, position.exceptionId(), NOW);
  }

  @Test
  void aReRunWithTheSameGapReusesTheDaysOpenException() {
    java.util.UUID open = java.util.UUID.randomUUID();
    when(store.trustClosing(DAY, "TRUST_MT940")).thenReturn(1_000_000L);
    when(store.openTrustException(DAY)).thenReturn(open);

    TrustPosition position = service.compute(DAY, 1_000_200L);

    assertThat(position.exceptionId()).isEqualTo(open);
    verify(store, never()).insertStandaloneException(any(), anyString(), anyString(), anyString(), anyString(), any(),
        any(), any());
    verify(store).updateStandaloneAmounts(open, 1_000_000L, 1_000_200L);
    verify(events).changed(open, null, NOW);
    verify(store).saveTrustPosition(DAY, 1_000_200L, 1_000_000L, -200L, open, NOW);
  }

  @Test
  void aCleanReRunResolvesTheDaysOpenExceptionAsSuperseded() {
    java.util.UUID open = java.util.UUID.randomUUID();
    when(store.trustClosing(DAY, "TRUST_MT940")).thenReturn(1_000_000L);
    when(store.openTrustException(DAY)).thenReturn(open);
    when(store.supersede(open, NOW)).thenReturn(1);

    TrustPosition position = service.compute(DAY, 1_000_000L);

    assertThat(position.exceptionId()).isNull();
    verify(store).supersede(open, NOW);
    verify(events).changed(open, null, NOW);
    verify(store).saveTrustPosition(DAY, 1_000_000L, 1_000_000L, 0L, null, NOW);
  }

  @Test
  void noClosingBalanceYetIsAnError() {
    when(store.trustClosing(DAY, "TRUST_MT940")).thenReturn(null);

    assertThatThrownBy(() -> service.compute(DAY, 5L)).isInstanceOf(MwException.class);
    verify(store, never()).saveTrustPosition(any(), anyLong(), anyLong(), anyLong(), any(), any());
  }
}
