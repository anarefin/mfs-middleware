package mfs.mw.simulators.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/** What the OCS really did with each transaction id; the answer to a status inquiry. */
@Service
public class OcsLedger {

  private final Map<String, TopupRecord> records = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  public void put(TopupRecord r) {
    records.put(r.transactionId(), r);
  }

  public Optional<TopupRecord> find(String transactionId) {
    return Optional.ofNullable(records.get(transactionId));
  }

  public int size() {
    return records.size();
  }

  public String nextRef() {
    return "OCS-" + refs.incrementAndGet();
  }
}
