package mfs.mw.simulators.service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.simulators.dto.PartnerStub;
import mfs.mw.simulators.dto.RecordedRequest;
import org.springframework.stereotype.Service;

/**
 * The generic programmable partner stub (ticket 83): canned answers per partner id and operation
 * (method + path), and the last {@value #RECORD_LIMIT} requests per partner. In memory; a restart
 * forgets everything. Any partner id works: nothing is configured up front.
 */
@Service
public class PartnerStubStore {

  static final int RECORD_LIMIT = 200;

  private final Clock clock;
  private final Map<String, List<PartnerStub>> stubs = new HashMap<>();
  private final Map<String, Deque<RecordedRequest>> requests = new HashMap<>();
  private long sequence;

  public PartnerStubStore(Clock clock) {
    this.clock = clock;
  }

  /** Adds {@code stub}, replacing one with the same method and path. */
  public synchronized void put(String partnerId, PartnerStub stub) {
    List<PartnerStub> list = stubs.computeIfAbsent(partnerId, k -> new ArrayList<>());
    list.removeIf(s -> s.method().equalsIgnoreCase(stub.method()) && s.path().equals(stub.path()));
    list.add(stub);
  }

  public synchronized List<PartnerStub> stubs(String partnerId) {
    return List.copyOf(stubs.getOrDefault(partnerId, List.of()));
  }

  /** Forgets the partner's stubs and recorded requests. */
  public synchronized void reset(String partnerId) {
    stubs.remove(partnerId);
    requests.remove(partnerId);
  }

  /** Records the request and returns the stub that answers it, if any. */
  public synchronized Optional<PartnerStub> answer(
      String partnerId, String method, String path, String query, Map<String, String> headers, String body) {
    Deque<RecordedRequest> q = requests.computeIfAbsent(partnerId, k -> new ArrayDeque<>());
    q.addLast(new RecordedRequest(++sequence, clock.instant(), method, path, query, Map.copyOf(headers), body));
    while (q.size() > RECORD_LIMIT) {
      q.removeFirst();
    }
    return stubs.getOrDefault(partnerId, List.of()).stream().filter(s -> s.matches(method, path)).findFirst();
  }

  public synchronized List<RecordedRequest> requests(String partnerId) {
    return List.copyOf(requests.getOrDefault(partnerId, new ArrayDeque<>()));
  }
}
