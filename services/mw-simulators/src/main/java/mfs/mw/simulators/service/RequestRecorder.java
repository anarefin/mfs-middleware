package mfs.mw.simulators.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.RecordedRequest;
import org.springframework.stereotype.Service;

/** Keeps the last {@code sim.record-limit} requests per partner for test assertions (spec 22.2). */
@Service
public class RequestRecorder {

  private final int limit;
  private final Map<String, Deque<RecordedRequest>> requests = new HashMap<>();
  private final Map<String, Long> sequences = new HashMap<>();

  public RequestRecorder(SimulatorProperties props) {
    this.limit = props.recordLimit();
  }

  public synchronized void record(
      String partner,
      String method,
      String path,
      String query,
      Map<String, String> headers,
      String body,
      Instant at) {
    long seq = sequences.merge(partner, 1L, Long::sum);
    Deque<RecordedRequest> q = requests.computeIfAbsent(partner, k -> new ArrayDeque<>());
    q.addLast(new RecordedRequest(seq, at, method, path, query, Map.copyOf(headers), body));
    while (q.size() > limit) {
      q.removeFirst();
    }
  }

  /** Recorded requests, oldest first; {@code pathContains} filters when not null. */
  public synchronized List<RecordedRequest> list(String partner, String pathContains) {
    return requests.getOrDefault(partner, new ArrayDeque<>()).stream()
        .filter(r -> pathContains == null || r.path().contains(pathContains))
        .toList();
  }

  public synchronized void clear(String partner) {
    requests.remove(partner);
  }
}
