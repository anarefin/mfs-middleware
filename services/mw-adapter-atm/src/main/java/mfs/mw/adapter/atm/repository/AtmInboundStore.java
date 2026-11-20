package mfs.mw.adapter.atm.repository;

import java.time.Instant;
import java.util.Optional;

/**
 * One row per (operation, the switch's atmTxnRef) of an inbound ATM call (tickets 120a, 120b1,
 * PLT-025), with the cash-out state on the VERIFY row.
 */
public interface AtmInboundStore {

  /**
   * A recorded inbound ATM call. The token is never stored (FL-11); the MSISDN only inside the
   * keyed {@code fingerprint}. {@code state} is the cash-out state of a VERIFY row, else null.
   */
  record Row(String txnId, String inboundId, String atmId, String fingerprint, String state) {}

  /** Inserts the row unless one exists; true when this call inserted it. */
  boolean insert(String operation, String atmTxnRef, Row row, Instant at);

  Optional<Row> find(String operation, String atmTxnRef);

  /** Moves the row's state from {@code from} to {@code to}; true when this call moved it. */
  boolean transition(String operation, String atmTxnRef, String from, String to);
}
