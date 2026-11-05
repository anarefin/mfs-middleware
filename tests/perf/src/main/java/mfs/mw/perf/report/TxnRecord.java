package mfs.mw.perf.report;

import java.time.Instant;
import java.util.List;
import mfs.mw.perf.mix.Flow;

/**
 * One transaction of a run as the simulation saw it: the flow, the txnId the middleware answered
 * with (null when none came back), when the submit was sent, its end-to-end time (the submit's
 * response time; for {@link Flow#USSD_P2P} the whole simulated session), the HTTP status, the error
 * code if any, and whether the answer was the one the flow expects (success, or the intended
 * business failure).
 */
public record TxnRecord(
    Flow flow, String txnId, Instant start, long e2eMs, int httpStatus, String errorCode, boolean expected) {

  static final String HEADER = "flow,txnId,startEpochMs,e2eMs,httpStatus,errorCode,expected";

  /** One CSV line (no commas or quotes can occur in any field). */
  public String toCsv() {
    return String.join(
        ",",
        flow.name(),
        txnId == null ? "" : txnId,
        Long.toString(start.toEpochMilli()),
        Long.toString(e2eMs),
        Integer.toString(httpStatus),
        errorCode == null ? "" : errorCode,
        Boolean.toString(expected));
  }

  public static TxnRecord fromCsv(String line) {
    String[] f = line.split(",", -1);
    if (f.length != 7) {
      throw new IllegalArgumentException("not a transaction log line: " + line);
    }
    return new TxnRecord(
        Flow.valueOf(f[0]),
        f[1].isEmpty() ? null : f[1],
        Instant.ofEpochMilli(Long.parseLong(f[2])),
        Long.parseLong(f[3]),
        Integer.parseInt(f[4]),
        f[5].isEmpty() ? null : f[5],
        Boolean.parseBoolean(f[6]));
  }

  /** Parses a whole log (header line first). */
  public static List<TxnRecord> parse(List<String> lines) {
    return lines.stream().skip(1).filter(l -> !l.isBlank()).map(TxnRecord::fromCsv).toList();
  }
}
