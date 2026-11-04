package mfs.mw.simulators.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/** What the aggregator really did with each transaction id; the answer to a status inquiry. */
@Service
public class BillPaymentLedger {

  private final Map<String, BillPaymentRecord> records = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  /** Stores the record unless one exists; returns the stored one (first writer wins). */
  public BillPaymentRecord putIfAbsent(BillPaymentRecord r) {
    BillPaymentRecord prior = records.putIfAbsent(r.transactionId(), r);
    return prior != null ? prior : r;
  }

  public Optional<BillPaymentRecord> find(String transactionId) {
    return Optional.ofNullable(records.get(transactionId));
  }

  public String nextRef() {
    return "AGG-" + refs.incrementAndGet();
  }
}
