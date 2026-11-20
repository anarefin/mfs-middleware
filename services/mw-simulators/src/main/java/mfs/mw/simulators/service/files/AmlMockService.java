package mfs.mw.simulators.service.files;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.simulators.client.RemoteFiles;
import mfs.mw.simulators.config.FileMockProperties;
import mfs.mw.simulators.dto.RecordedRequest;
import mfs.mw.simulators.service.SeedFiles;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import mfs.mw.simulators.util.Hashing;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The AML vendor mock, party {@code aml} (ticket 123, FRS EXT-05): every new file in the inbox ({@code aml/in}) is
 * read (journal READ) and answered by {@link AmlResponder} in the outbox ({@code aml/out}) through {@link
 * FileMocks} (operation {@code respond}, keys {@code file} and {@code date}, today in Asia/Dhaka). A file is
 * answered once per name and content (a changed file is answered again); the party reset forgets which.
 */
@Service
@Profile(MockControl.PROFILE)
public class AmlMockService implements PartyState {

  static final String PARTY = "aml";

  private final RemoteFiles files;
  private final FileMocks mocks;
  private final FileMockProperties.Aml props;
  private final AmlResponder responder;
  private final Clock clock;
  private final Set<String> answered = new HashSet<>();

  public AmlMockService(RemoteFiles files, FileMocks mocks, FileMockProperties props, Clock clock) {
    this.files = files;
    this.mocks = mocks;
    this.props = props.aml();
    this.responder = new AmlResponder(SeedFiles.amlRules(this.props.rulesVersion()));
    this.clock = clock;
  }

  /** Answers every new transaction file; returns the journal entries recorded. */
  public synchronized List<RecordedRequest> run() {
    List<RecordedRequest> out = new ArrayList<>();
    String today = LocalDate.now(clock.withZone(TrustStatementGenerator.DHAKA)).toString();
    for (String name : files.list(props.inbox())) {
      String path = props.inbox() + "/" + name;
      byte[] content = files.read(path);
      if (!answered.add(name + "|" + Hashing.sha256Hex(content))) {
        continue;
      }
      out.add(mocks.recordRead(PARTY, path, content, Map.of()));
      out.addAll(mocks.produce(PARTY, "respond", Map.of("file", name, "date", today),
          props.outbox() + "/" + AmlResponder.responseName(name), m -> responder.respond(name, content, m).content()));
    }
    return out;
  }

  @Override
  public synchronized void reset(String party) {
    if (PARTY.equals(party)) {
      answered.clear();
    }
  }
}
