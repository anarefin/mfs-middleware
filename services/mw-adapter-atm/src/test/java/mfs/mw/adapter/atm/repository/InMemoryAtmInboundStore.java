package mfs.mw.adapter.atm.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link AtmInboundStore} without a database, for the slice and unit tests (ticket 120b). */
public final class InMemoryAtmInboundStore implements AtmInboundStore {

  private final ConcurrentHashMap<String, Row> rows = new ConcurrentHashMap<>();

  @Override
  public boolean insert(String operation, String atmTxnRef, Row row, Instant at) {
    return rows.putIfAbsent(key(operation, atmTxnRef), row) == null;
  }

  @Override
  public Optional<Row> find(String operation, String atmTxnRef) {
    return Optional.ofNullable(rows.get(key(operation, atmTxnRef)));
  }

  @Override
  public boolean transition(String operation, String atmTxnRef, String from, String to) {
    String key = key(operation, atmTxnRef);
    Row row = rows.get(key);
    return row != null && from.equals(row.state())
        && rows.replace(key, row, new Row(row.txnId(), row.inboundId(), row.atmId(), row.fingerprint(), to));
  }

  private static String key(String operation, String atmTxnRef) {
    return operation + "\n" + atmTxnRef;
  }
}
