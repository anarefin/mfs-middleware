package mfs.mw.simulators.service.files;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.simulators.client.RemoteFiles;
import mfs.mw.simulators.config.FileMockProperties;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.service.SeedFiles;
import mfs.mw.simulators.service.files.DwDropValidator.Verdict;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import mfs.mw.simulators.util.Hashing;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The Data Warehouse drop mock, party {@code dw} (ticket 123, FRS EXT-17): every new control file {@code
 * control_<date>.json} in {@code dw/in} is checked with the files it lists by {@link DwDropValidator}; the journal
 * gets one READ per listed file (header {@code verdict}: OK or its problem) and one for the control file (headers
 * {@code verdict} ACCEPTED or REJECTED and {@code reasons}). Operation {@code validate} (keys {@code file}, {@code
 * date}): a BUSINESS_ERROR scenario rejects the drop whatever it holds (reason {@code FORCED <code or
 * DW_REJECT>}). A control file is checked once per name and content; the party reset forgets which.
 */
@Service
@Profile(MockControl.PROFILE)
public class DwDropService implements PartyState {

  static final String PARTY = "dw";

  private final RemoteFiles files;
  private final FileMocks mocks;
  private final MockControl control;
  private final FileMockProperties.Dw props;
  private final DwDropValidator validator;
  private final Clock clock;
  private final Set<String> checked = new HashSet<>();

  public DwDropService(RemoteFiles files, FileMocks mocks, MockControl control, FileMockProperties props, Clock clock) {
    this.files = files;
    this.mocks = mocks;
    this.control = control;
    this.props = props.dw();
    this.validator = new DwDropValidator(SeedFiles.dwSchemas(SeedFiles.VERSION));
    this.clock = clock;
  }

  /** Checks every new drop; returns the journal entries recorded. */
  public synchronized List<RecordedRequest> run() {
    List<RecordedRequest> out = new ArrayList<>();
    String inbox = props.inbox();
    List<String> names = files.list(inbox);
    String today = LocalDate.now(clock.withZone(TrustStatementGenerator.DHAKA)).toString();
    for (String name : names) {
      if (!name.startsWith("control_") || !name.endsWith(".json")) {
        continue;
      }
      byte[] ctl = files.read(inbox + "/" + name);
      if (!checked.add(name + "|" + Hashing.sha256Hex(ctl))) {
        continue;
      }
      Map<String, byte[]> read = new LinkedHashMap<>();
      Verdict v = validator.validate(ctl, f -> {
        if (f.isBlank() || f.contains("/") || !names.contains(f)) {
          return null;
        }
        byte[] b = files.read(inbox + "/" + f);
        read.put(f, b);
        return b;
      });
      String date = name.substring("control_".length(), name.length() - ".json".length());
      Decision d = control.decide(PARTY, "validate", Map.of("file", name, "date", date.isBlank() ? today : date),
          SimMode.SUCCESS);
      List<String> reasons = new ArrayList<>(v.reasons());
      if (d.mode() == SimMode.BUSINESS_FAILURE) {
        reasons.add("FORCED " + d.codeOr("DW_REJECT"));
      }
      String status = reasons.isEmpty() ? "ACCEPTED" : "REJECTED";
      read.forEach((f, b) -> out.add(mocks.recordRead(PARTY, inbox + "/" + f, b,
          Map.of("verdict", v.files().getOrDefault(f, "OK")))));
      out.add(mocks.recordRead(PARTY, inbox + "/" + name, ctl,
          Map.of("verdict", status, "reasons", String.join("; ", reasons))));
    }
    return out;
  }

  @Override
  public synchronized void reset(String party) {
    if (PARTY.equals(party)) {
      checked.clear();
    }
  }
}
