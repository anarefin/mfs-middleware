package mfs.mw.simulators.service.files;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import mfs.mw.simulators.client.RemoteFiles;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.service.files.TrustStatementGenerator.Mismatch;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * Delivery and journal of the file mocks (ticket 123, FRS MCT-05, MCK-07): asks the engine how the next file of a
 * party goes ({@link MockControl#fileFault}), builds it (an INJECTED_MISMATCH reaches the builder as a {@link
 * Mismatch}), writes it to the file host and records every file written, skipped or read in the party's {@link
 * Journal} with its SHA-256: {@code method} WRITE, SKIP (MISSING_FILE), LATE (a LATE_FILE scheduled; its WRITE
 * follows) or READ; {@code path} the file; headers {@code sha256}, {@code bytes}, {@code fault} (when one applied)
 * and what the mock adds (a verdict); {@code body} the text when it is at most {@value #BODY_LIMIT} bytes.
 *
 * <p>Faults: MISSING_FILE writes nothing; LATE_FILE writes after {@code delayMs} (default the party's timeout
 * hold); DUPLICATE_FILE writes the file and a copy {@code <name>_DUP<ext>}; CORRUPT_FILE writes the first half
 * followed by {@link #CORRUPTION}.
 */
@Service
@Profile(MockControl.PROFILE)
public class FileMocks {

  /** What a CORRUPT_FILE ends with after its first half. */
  public static final String CORRUPTION = "\u0000#CORRUPT#";

  static final int BODY_LIMIT = 64 * 1024;

  private static final Logger log = LoggerFactory.getLogger(FileMocks.class);

  private final MockControl control;
  private final RemoteFiles files;
  private final Journal journal;
  private final TaskScheduler scheduler;
  private final SimulatorProperties props;
  private final Clock clock;

  public FileMocks(MockControl control, RemoteFiles files, Journal journal, TaskScheduler scheduler,
      SimulatorProperties props, Clock clock) {
    this.control = control;
    this.files = files;
    this.journal = journal;
    this.scheduler = scheduler;
    this.props = props;
    this.clock = clock;
  }

  /** Builds and delivers one file of {@code party}; answers the journal entries this call recorded. */
  public List<RecordedRequest> produce(
      String party, String operation, Map<String, String> keys, String path, Function<Mismatch, byte[]> build) {
    Optional<MockScenario> fault = control.fileFault(party, operation, keys);
    Mismatch mismatch = fault
        .filter(s -> s.behaviour() == mfs.mw.simulators.dto.MockBehaviour.INJECTED_MISMATCH)
        .map(s -> new Mismatch(s.code(), s.count() == null ? 1 : s.count()))
        .orElse(Mismatch.NONE);
    byte[] content = build.apply(mismatch);
    List<RecordedRequest> out = new ArrayList<>();
    if (fault.isEmpty()) {
      out.add(write(party, path, content, null));
      return out;
    }
    MockScenario s = fault.get();
    switch (s.behaviour()) {
      case MISSING_FILE -> out.add(record(party, "SKIP", path, content, Map.of("fault", "MISSING_FILE")));
      case LATE_FILE -> {
        long delay = s.delayMs() != null ? s.delayMs() : hold(party);
        out.add(record(party, "LATE", path, content, Map.of("fault", "LATE_FILE", "delayMs", Long.toString(delay))));
        scheduler.schedule(() -> {
          try {
            write(party, path, content, "LATE_FILE");
          } catch (RuntimeException e) {
            log.atWarn().addKeyValue("event", "sim.files.late_write_failed").addKeyValue("partnerId", party)
                .log("late file of {} not written: {}", party, e.getClass().getSimpleName());
          }
        }, scheduler.getClock().instant().plusMillis(delay));
      }
      case DUPLICATE_FILE -> {
        out.add(write(party, path, content, null));
        out.add(write(party, duplicateName(path), content, "DUPLICATE_FILE"));
      }
      case CORRUPT_FILE -> out.add(write(party, path, corrupt(content), "CORRUPT_FILE"));
      case INJECTED_MISMATCH -> out.add(write(party, path, content,
          "INJECTED_MISMATCH:" + mismatch.kind() + ":" + mismatch.count()));
      default -> out.add(write(party, path, content, null));
    }
    return out;
  }

  /** Records a file a mock read, with what it found ({@code extra}: a verdict, a reason). */
  public RecordedRequest recordRead(String party, String path, byte[] content, Map<String, String> extra) {
    return record(party, "READ", path, content, extra);
  }

  private RecordedRequest write(String party, String path, byte[] content, String fault) {
    files.write(path, content);
    log.atInfo().addKeyValue("event", "sim.files.written").addKeyValue("partnerId", party)
        .log("{} wrote {} ({} bytes{})", party, path, content.length, fault == null ? "" : ", " + fault);
    return record(party, "WRITE", path, content, fault == null ? Map.of() : Map.of("fault", fault));
  }

  private RecordedRequest record(String party, String method, String path, byte[] content, Map<String, String> extra) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("sha256", Hashing.sha256Hex(content));
    headers.put("bytes", Integer.toString(content.length));
    headers.putAll(extra);
    String body = content.length <= BODY_LIMIT ? new String(content, StandardCharsets.UTF_8) : null;
    return journal.record(party, method, path, null, headers, body, Instant.now(clock));
  }

  private long hold(String party) {
    Long t = control.settings(party).timeoutDelayMs();
    return t != null ? t : props.timeoutDelay().toMillis();
  }

  static String duplicateName(String path) {
    int slash = path.lastIndexOf('/');
    int dot = path.lastIndexOf('.');
    return dot > slash ? path.substring(0, dot) + "_DUP" + path.substring(dot) : path + "_DUP";
  }

  static byte[] corrupt(byte[] content) {
    byte[] half = Arrays.copyOf(content, content.length / 2);
    byte[] marker = CORRUPTION.getBytes(StandardCharsets.UTF_8);
    byte[] out = Arrays.copyOf(half, half.length + marker.length);
    System.arraycopy(marker, 0, out, half.length, marker.length);
    return out;
  }
}
