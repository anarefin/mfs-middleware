package mfs.mw.audit.dw.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Verifies the hash chain of every month (spec 14.3, ticket 50), read through the read-only
 * {@code audit_reader} role. A broken chain, or a month that cannot be read, is an ERROR business
 * event {@code audit.chain.broken} (month, failedSeq, reason; never row contents) and counts in
 * the gauge {@code mw.audit.chain.broken} (broken months found by the last run; 0 = intact).
 */
@Service
@ConditionalOnProperty(name = "mw.audit-dw.chain-verify.enabled", havingValue = "true", matchIfMissing = true)
public class ChainVerifyService {

  private static final Logger log = LoggerFactory.getLogger(ChainVerifyService.class);

  private final AuditChainVerifier verifier;
  private final AtomicInteger broken = new AtomicInteger();

  public ChainVerifyService(AuditChainVerifier verifier, MeterRegistry meters) {
    this.verifier = verifier;
    Gauge.builder("mw.audit.chain.broken", broken, AtomicInteger::get)
        .description("Audit months whose hash chain failed the last verification")
        .register(meters);
  }

  public List<ChainVerification> verifyAll() {
    List<ChainVerification> results = new ArrayList<>();
    for (String month : verifier.months()) {
      ChainVerification v;
      try {
        v = verifier.verify(month);
      } catch (RuntimeException e) {
        v = new ChainVerification(month, false, 0, null, "unreadable: " + e.getClass().getSimpleName());
      }
      results.add(v);
      if (!v.intact()) {
        log.atError()
            .addKeyValue("event", "audit.chain.broken")
            .addKeyValue("month", v.month())
            .addKeyValue("failedSeq", v.failedSeq())
            .addKeyValue("reason", v.reason())
            .log("audit hash chain verification failed");
      }
    }
    int count = (int) results.stream().filter(v -> !v.intact()).count();
    broken.set(count);
    log.atInfo()
        .addKeyValue("event", "audit.chain.verified")
        .addKeyValue("months", results.size())
        .addKeyValue("broken", count)
        .log("audit hash chains verified");
    return results;
  }
}
