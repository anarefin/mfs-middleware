package mfs.mw.simulators.service.files;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.client.CoreLedgerClient;
import mfs.mw.simulators.config.FileMockProperties;
import mfs.mw.simulators.dto.LedgerEntry;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.service.files.TrustStatementGenerator.TrustAccount;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The trust bank mock, party {@code trustbank} (ticket 123, FRS EXT-06): the daily MT940 ({@code
 * MT940_<account>_<yyyyMMdd>.fin}) and the intraday MT942 ({@code MT942_<account>_<yyyyMMdd>_<HHmm>.fin}) of the
 * trust account in {@code trustbank/out}, from the ledger of the mw-core-mock wallet {@code walletId} (default
 * SETTLEMENT) read through {@link CoreLedgerClient}, built by {@link TrustStatementGenerator} and delivered by
 * {@link FileMocks} (operations {@code mt940} / {@code mt942}, keys {@code date} yyyy-MM-dd and {@code account}).
 * The opening balance is the first entry's {@code balanceAfter} less that entry; without one (a day without
 * entries, or entries older than ticket 94) the wallet's balance now less the day's entries, which is exact only
 * when nothing was posted after the day (a test double's approximation).
 */
@Service
@Profile(MockControl.PROFILE)
public class TrustBankService {

  static final String PARTY = "trustbank";
  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

  private final CoreLedgerClient core;
  private final FileMocks mocks;
  private final FileMockProperties.TrustBank props;
  private final TrustStatementGenerator generator = new TrustStatementGenerator();

  public TrustBankService(CoreLedgerClient core, FileMocks mocks, FileMockProperties props) {
    this.core = core;
    this.mocks = mocks;
    this.props = props.trustbank();
  }

  private TrustAccount account() {
    return new TrustAccount(props.senderBic(), props.receiverBic(), props.account());
  }

  /** Writes the MT940 of {@code date} (Asia/Dhaka). */
  public synchronized List<RecordedRequest> mt940(LocalDate date) {
    Instant from = date.atStartOfDay(TrustStatementGenerator.DHAKA).toInstant();
    Instant to = date.plusDays(1).atStartOfDay(TrustStatementGenerator.DHAKA).toInstant();
    List<LedgerEntry> entries = core.entries(props.walletId(), from, to);
    long opening = opening(entries);
    String path = props.outbox() + "/MT940_" + props.account() + "_" + YYYYMMDD.format(date) + ".fin";
    return mocks.produce(PARTY, "mt940", Map.of("date", date.toString(), "account", props.account()), path,
        m -> generator.mt940(account(), date, opening, entries, m).getBytes(StandardCharsets.UTF_8));
  }

  /** Writes the MT942 of {@code date} from its start up to {@code at} (Asia/Dhaka). */
  public synchronized List<RecordedRequest> mt942(LocalDate date, LocalTime at) {
    Instant from = date.atStartOfDay(TrustStatementGenerator.DHAKA).toInstant();
    Instant to = date.atTime(at).atZone(TrustStatementGenerator.DHAKA).toInstant();
    List<LedgerEntry> entries = core.entries(props.walletId(), from, to);
    String path = props.outbox() + "/MT942_" + props.account() + "_" + YYYYMMDD.format(date) + "_" + HHMM.format(at) + ".fin";
    return mocks.produce(PARTY, "mt942", Map.of("date", date.toString(), "account", props.account()), path,
        m -> generator.mt942(account(), date, at, entries, m).getBytes(StandardCharsets.UTF_8));
  }

  private long opening(List<LedgerEntry> entries) {
    if (!entries.isEmpty() && entries.get(0).balanceAfterMinor() != null) {
      LedgerEntry first = entries.get(0);
      return first.balanceAfterMinor() + (first.credit() ? -first.amountMinor() : first.amountMinor());
    }
    long now = core.balanceMinor(props.walletId());
    for (LedgerEntry e : entries) {
      now += e.credit() ? -e.amountMinor() : e.amountMinor();
    }
    return now;
  }
}
