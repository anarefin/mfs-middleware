package mfs.mw.adapter.atm.repository;

import java.time.Instant;
import java.util.Optional;

/** One row per (operation, the switch's atmTxnRef) of an inbound ATM call (ticket 120a, PLT-025). */
public interface AtmInboundStore {

  /** A recorded inbound ATM call. The token is never stored (FL-11). */
  record Row(String txnId, String inboundId) {}

  /** Inserts the row unless one exists; true when this call inserted it. */
  boolean insert(String operation, String atmTxnRef, String txnId, String inboundId, Instant at);

  Optional<Row> find(String operation, String atmTxnRef);
}
