package mfs.mw.recon.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import mfs.mw.recon.client.CoreEmoneyClient;
import mfs.mw.recon.dto.TrustPosition;
import mfs.mw.recon.service.TrustPositionService;
import org.junit.jupiter.api.Test;

/** REC-07 (ticket 127c): after the MT940 cut-off, yesterday's Asia/Dhaka position from the core's e-money total. */
class TrustPositionJobTest {

  // 2026-11-20 00:45 in Dhaka (UTC+6): the business date is 2026-11-19
  private static final Instant NOW = Instant.parse("2026-11-19T18:45:00Z");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 19);

  private final CoreEmoneyClient core = mock(CoreEmoneyClient.class);
  private final TrustPositionService positions = mock(TrustPositionService.class);
  private final TrustPositionJob job =
      new TrustPositionJob(core, positions, "Asia/Dhaka", Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void yesterdaysPositionIsComputedFromTheCoreTotal() {
    when(positions.closingKnown(DAY)).thenReturn(true);
    when(core.emoneyTotalMinor()).thenReturn(500L);
    TrustPosition expected = new TrustPosition(DAY, 500L, 500L, 0L, null);
    when(positions.compute(DAY, 500L)).thenReturn(expected);

    job.run();

    verify(positions).compute(DAY, 500L);
  }

  @Test
  void daysWithoutAPositionSinceTheLastOneAreReportedAsMissed() {
    when(positions.lastPositionDate()).thenReturn(DAY.minusDays(3));
    when(positions.positionKnown(DAY.minusDays(1))).thenReturn(true);

    assertThat(job.missedDays(DAY)).containsExactly(DAY.minusDays(2));
  }

  @Test
  void noPositionYetMissesNothing() {
    when(positions.lastPositionDate()).thenReturn(null);

    assertThat(job.missedDays(DAY)).isEmpty();
  }

  @Test
  void noStatementForTheDayYetAsksTheCoreNothing() {
    when(positions.closingKnown(DAY)).thenReturn(false);

    assertThat(job.runFor(DAY)).isNull();

    verify(core, never()).emoneyTotalMinor();
    verify(positions, never()).compute(any(), anyLong());
  }
}
