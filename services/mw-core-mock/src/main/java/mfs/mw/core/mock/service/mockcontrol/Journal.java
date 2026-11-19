package mfs.mw.core.mock.service.mockcontrol;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.core.mock.config.MockControlProperties;
import mfs.mw.core.mock.dto.RecordedRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The request journal of the core party (FRS MCK-07, MCT-06; copied from mw-simulators, ticket 103):
 * the last {@code mw.coremock.mock-control.journal-limit} commands the core handled, REST or Kafka, for
 * test assertions. Bounded, in memory. An entry is the command, not its body: method {@code COMMAND},
 * path {@code /<operation>/<commandType>}, headers {@code x-txn-id}, body the scope keys (wallet ids,
 * holdId, ...) as {@code key=value} lines; never an assertion.
 */
@Service
@Profile(MockControl.PROFILE)
public class Journal {

  private final int limit;
  private final Map<String, Deque<RecordedRequest>> requests = new HashMap<>();
  private final Map<String, Long> sequences = new HashMap<>();

  public Journal(MockControlProperties props) {
    this.limit = props.journalLimit();
  }

  public synchronized void record(
      String party, String method, String path, String query, Map<String, String> headers, String body, Instant at) {
    long seq = sequences.merge(party, 1L, Long::sum);
    Deque<RecordedRequest> q = requests.computeIfAbsent(party, k -> new ArrayDeque<>());
    q.addLast(new RecordedRequest(seq, at, method, path, query, Map.copyOf(headers), body));
    while (q.size() > limit) {
      q.removeFirst();
    }
  }

  /**
   * The party's entries, oldest first, filtered by every criterion that is not null.
   *
   * @param pathContains part of the path (an operation or command type)
   * @param bodyContains part of the body (an MSISDN, a holdId, ...)
   * @param afterSeq only entries recorded after this sequence number
   */
  public synchronized List<RecordedRequest> query(String party, String pathContains, String bodyContains, Long afterSeq) {
    MockParties.require(party);
    return requests.getOrDefault(party, new ArrayDeque<>()).stream()
        .filter(r -> pathContains == null || r.path().contains(pathContains))
        .filter(r -> bodyContains == null || (r.body() != null && r.body().contains(bodyContains)))
        .filter(r -> afterSeq == null || r.seq() > afterSeq)
        .toList();
  }

  /** Forgets the party's entries; sequence numbers keep counting so a test's {@code afterSeq} stays valid. */
  public synchronized void clear(String party) {
    MockParties.require(party);
    requests.remove(party);
  }
}
