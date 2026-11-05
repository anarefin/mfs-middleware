package mfs.mw.perf.report;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import mfs.mw.perf.stack.Stack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The time spent outside the middleware per transaction, read after the run with read-only queries
 * (psql in the stack's postgres container, as the e2e suite reads the audit trail):
 *
 * <ul>
 *   <li>core: for every reply the core mock wrote ({@code coremock.outbox}, key = txnId), from the
 *       start of the core's database transaction that handled the command ({@code created_at} is
 *       {@code now()}) to the relay's send ({@code sent_at}): the mock's processing plus its own
 *       outbox relay wait. Kafka transit to and from the core stays in the overhead (conservative).
 *   <li>partner: {@code durationMs} of every {@code partner.call.completed} audit event the adapters
 *       wrote (HTTP call to the simulator, network included).
 * </ul>
 */
public final class RunFacts {

  private static final Logger log = LoggerFactory.getLogger(RunFacts.class);

  private RunFacts() {}

  static String coreSql(Instant since) {
    return "copy (select msg_key, round(sum(extract(epoch from (sent_at - created_at)) * 1000))::bigint"
        + " from coremock.outbox where created_at >= '" + since + "' and sent_at is not null"
        + " group by msg_key) to stdout with csv";
  }

  static String partnerSql(Instant since) {
    return "copy (select txn_id, sum((after->>'durationMs')::bigint) from audit.event"
        + " where action = 'partner.call.completed' and recorded_at >= '" + since + "' and txn_id is not null"
        + " group by txn_id) to stdout with csv";
  }

  static Map<String, Long> parseSums(String csv) {
    Map<String, Long> sums = new HashMap<>();
    for (String line : csv.split("\n")) {
      String l = line.strip();
      int comma = l.lastIndexOf(',');
      if (comma > 0) {
        sums.put(l.substring(0, comma), Long.parseLong(l.substring(comma + 1)));
      }
    }
    return sums;
  }

  public static Map<String, Long> coreMs(Instant since) {
    return parseSums(Stack.get().psql(coreSql(since)));
  }

  /**
   * Partner time; first waits (up to {@code maxWait}) until mw-audit-dw has stored the adapters'
   * audit events of the run: the count must stop growing for one poll.
   */
  public static Map<String, Long> partnerMs(Instant since, Duration maxWait) {
    Instant deadline = Instant.now().plus(maxWait);
    String countSql = "select count(*) from audit.event where action = 'partner.call.completed' and recorded_at >= '" + since + "'";
    long last = -1;
    while (Instant.now().isBefore(deadline)) {
      long now = Long.parseLong(Stack.get().psql(countSql).strip());
      if (now == last) {
        break;
      }
      last = now;
      log.info("event=perf.report.audit.wait partnerCalls={}", now);
      sleep(Duration.ofSeconds(5));
    }
    return parseSums(Stack.get().psql(partnerSql(since)));
  }

  /** Middleware outbox schemas whose relay wait is a known overhead suspect (500 ms relay period when idle). */
  private static final Map<String, String> OUTBOXES =
      Map.of("mw-orchestrator", "txn", "mw-app-gateway", "app_gateway", "mw-adapter-telco", "partner_ref");

  /**
   * Hints for a gap: p95 and max of the time the middleware's own outbox rows waited for their
   * relay during the run (sent_at - created_at). A schema that cannot be read is reported as such.
   */
  public static Map<String, String> diagnostics(Instant since) {
    Map<String, String> out = new java.util.TreeMap<>();
    OUTBOXES.forEach((service, schema) -> {
      String label = "outbox relay wait p95 / max, " + service + " (" + schema + ".outbox)";
      try {
        String row = Stack.get().psql(
            "select coalesce(round(percentile_cont(0.95) within group (order by extract(epoch from (sent_at - created_at)) * 1000))::bigint, 0)"
                + " || ' ms / ' || coalesce(round(max(extract(epoch from (sent_at - created_at)) * 1000))::bigint, 0) || ' ms'"
                + " from " + schema + ".outbox where created_at >= '" + since + "' and sent_at is not null");
        out.put(label, row.strip());
      } catch (RuntimeException e) {
        out.put(label, "not readable (" + e.getMessage() + ")");
      }
    });
    return out;
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
