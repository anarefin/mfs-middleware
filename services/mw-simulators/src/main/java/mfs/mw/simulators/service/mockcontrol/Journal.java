package mfs.mw.simulators.service.mockcontrol;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.RecordedRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The request journal of every mock party (FRS MCK-07, MCT-06): the last {@code sim.record-limit}
 * requests per party, whatever the answer, for test assertions. Bounded, in memory, test data
 * (bodies raw; {@code filter/RecordingFilter} drops Authorization and Cookie and blanks WS-Security
 * passwords before recording).
 */
@Service
@Profile(MockControl.PROFILE)
public class Journal {

  private final int limit;
  private final Map<String, Deque<RecordedRequest>> requests = new HashMap<>();
  private final Map<String, Long> sequences = new HashMap<>();

  public Journal(SimulatorProperties props) {
    this.limit = props.recordLimit();
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
   * The party's requests, oldest first, filtered by every criterion that is not null.
   *
   * @param pathContains part of the path
   * @param bodyContains part of the body (an MSISDN, a transaction id, ...)
   * @param afterSeq only requests recorded after this sequence number
   */
  public synchronized List<RecordedRequest> query(String party, String pathContains, String bodyContains, Long afterSeq) {
    MockParties.require(party);
    return requests.getOrDefault(party, new ArrayDeque<>()).stream()
        .filter(r -> pathContains == null || r.path().contains(pathContains))
        .filter(r -> bodyContains == null || (r.body() != null && r.body().contains(bodyContains)))
        .filter(r -> afterSeq == null || r.seq() > afterSeq)
        .toList();
  }

  /** Forgets the party's requests; sequence numbers keep counting so a test's {@code afterSeq} stays valid. */
  public synchronized void clear(String party) {
    MockParties.require(party);
    requests.remove(party);
  }
}
